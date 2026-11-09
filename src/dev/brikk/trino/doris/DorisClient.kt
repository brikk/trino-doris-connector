/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.trino.doris

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import com.google.inject.Inject
import com.mysql.cj.jdbc.JdbcStatement
import io.airlift.log.Logger
import io.trino.plugin.base.mapping.IdentifierMapping
import io.trino.plugin.jdbc.BaseJdbcClient
import io.trino.plugin.jdbc.BaseJdbcConfig
import io.trino.plugin.jdbc.ColumnMapping
import io.trino.plugin.jdbc.ConnectionFactory
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcJoinCondition
import io.trino.plugin.jdbc.JdbcJoinPushdownUtil
import io.trino.plugin.jdbc.JdbcErrorCode.JDBC_ERROR
import io.trino.plugin.jdbc.JdbcExpression
import io.trino.plugin.jdbc.JdbcSortItem
import io.trino.plugin.jdbc.JdbcSplit
import io.trino.plugin.jdbc.JdbcTableHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.plugin.jdbc.QueryBuilder
import io.trino.plugin.jdbc.PreparedQuery
import io.trino.plugin.jdbc.RemoteTableName
import io.trino.plugin.jdbc.WriteMapping
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.plugin.jdbc.logging.RemoteQueryModifier
import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import io.trino.spi.connector.AggregateFunction
import io.trino.spi.connector.ColumnHandle
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.connector.JoinStatistics
import io.trino.spi.connector.JoinType
import io.trino.spi.connector.SchemaTableName
import io.trino.spi.connector.SortOrder
import io.trino.spi.connector.TableNotFoundException
import io.trino.spi.statistics.TableStatistics
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.type.Type
import io.trino.spi.type.BigintType
import io.trino.spi.type.BooleanType
import io.trino.spi.type.DateType
import io.trino.spi.type.DecimalType
import io.trino.spi.type.IntegerType
import io.trino.spi.type.SmallintType
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TinyintType
import io.trino.spi.type.TypeManager
import io.trino.spi.type.VarcharType
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.function.BiFunction

/**
 * Read-only Doris client on the Base JDBC / SingleStore shape (PLAN G1; SR K1).
 *
 * Doris databases surface as JDBC *catalogs* on the MySQL wire (PROBE §10), so — exactly
 * like SingleStore/MySQL — schema listing reads catalogs and `TABLE_CAT` is the remote
 * schema name. Doris `internal` catalog only; system schemas hidden (PLAN G9).
 *
 * Column metadata is read from `information_schema.columns.COLUMN_TYPE`, NEVER from
 * `DatabaseMetaData.getColumns`, which is proven lossy on Doris — it narrows
 * LARGEINT to INT, reports IPV4/IPV6/VARIANT as OTHER, BITMAP as BIT, and reports only the
 * element type for ARRAY (PROBE §1, Impl #1; ledger global rule 2). This also removes
 * stock's per-scan companion `SELECT *` RSMD probe (ledger §E refinement).
 */
class DorisClient @Inject constructor(
    config: BaseJdbcConfig,
    dorisConfig: DorisConfig,
    connectionFactory: ConnectionFactory,
    queryBuilder: QueryBuilder,
    typeManager: TypeManager,
    identifierMapping: IdentifierMapping,
    queryModifier: RemoteQueryModifier,
    cancelConnectionFactory: DorisCancelConnectionFactory,
) : BaseJdbcClient(
    "`",
    connectionFactory,
    queryBuilder,
    config.jdbcTypesMappedToVarchar,
    identifierMapping,
    // execute()/insert paths apply BaseJdbcClient.queryModifier (both denied by the read-only
    // guard); the scan path gets the query-id comment via DorisQueryBuilder. Wrapped here too
    // so every conceivable remote statement carries the Trino query id (PLAN §8).
    DorisRemoteQueryModifier(queryModifier),
    false,
) {
    private val typeMapping = DorisTypeMapping(typeManager)

    /** Cluster-scoped cancellation (multi-FE/LB correct) — see [DorisClusterScopedCancel]. */
    private val clusterScopedCancel = DorisClusterScopedCancel(cancelConnectionFactory, dorisConfig.isClusterScopedCancel())

    private val rewriters = DorisPushdownRewriters(typeMapping, ::quoted)

    private val statisticsReader = DorisTableStatisticsReader(::quoted)

    override fun convertPredicate(
        session: ConnectorSession,
        expression: ConnectorExpression,
        assignments: Map<String, ColumnHandle>,
    ): Optional<ParameterizedExpression> {
        val rewritten = rewriters.predicate.rewrite(session, expression, assignments)
        logPushdown("pushdown", "unsupported shape", expression, rewritten.map { it.expression() })
        return rewritten
    }

    override fun listSchemas(connection: Connection): Collection<String> {
        // Doris exposes databases as JDBC catalogs; information_schema.schemata lists them
        // (PROBE §10). DatabaseMetaData.getSchemas() is not the right namespace here.
        try {
            connection.prepareStatement("SELECT SCHEMA_NAME FROM information_schema.schemata").use { statement ->
                statement.executeQuery().use { resultSet -> return readSchemaNames(resultSet) }
            }
        } catch (e: SQLException) {
            throw TrinoException(JDBC_ERROR, e)
        }
    }

    private fun readSchemaNames(resultSet: ResultSet): Collection<String> {
        val schemaNames = ImmutableSet.builder<String>()
        while (resultSet.next()) {
            val schemaName = resultSet.getString("SCHEMA_NAME")
            if (filterRemoteSchema(schemaName)) {
                schemaNames.add(schemaName)
            }
        }
        return schemaNames.build()
    }

    override fun filterRemoteSchema(schemaName: String): Boolean {
        // G9: hide Doris system schemas (PROBE §10 catalog list).
        return schemaName.lowercase(Locale.ENGLISH) !in HIDDEN_SCHEMAS
    }

    override fun getTables(connection: Connection, schemaName: Optional<String>, tableName: Optional<String>): ResultSet {
        // Doris maps databases to JDBC catalogs (same pattern as SingleStore/MySQL at 483).
        val metadata: DatabaseMetaData = connection.metaData
        return metadata.getTables(
            schemaName.orElse(null),
            null,
            escapeObjectNameForMetadataQuery(tableName, metadata.searchStringEscape).orElse(null),
            getTableTypes().map { it.toTypedArray() }.orElse(null),
        )
    }

    override fun getTableTypes(): Optional<List<String>> = Optional.of(ImmutableList.of("TABLE", "VIEW"))

    override fun getTableRemoteSchemaName(resultSet: ResultSet): String = resultSet.getString("TABLE_CAT")

    /**
     * P5 cost-aware join pushdown (PLAN §6.5; OFF by default via `join-pushdown.enabled`).
     * Joins arrive on the LEGACY path only (complex join pushdown is force-disabled in
     * [DorisClientModule] — no variable-comparison rewrite rules exist). Shape gates:
     * - INNER only. Outer joins stay local until effective output nullability can be
     *   propagated; retaining base NOT NULL flags makes min_by/max_by unsafe (R-04).
     * - AUTOMATIC strategy consults [JdbcJoinPushdownUtil.shouldPushDownJoinCostAware],
     *   which feeds on the engine estimates derived from [getTableStatistics] — tables
     *   without statistics yield unknown sizes and stay LOCAL under AUTOMATIC (fail-soft
     *   stats feeding a conservative cost decision); EAGER pushes any eligible shape.
     * Condition gates are in [isSupportedJoinCondition].
     */
    override fun legacyImplementJoin(
        session: ConnectorSession,
        joinType: JoinType,
        leftSource: PreparedQuery,
        rightSource: PreparedQuery,
        joinConditions: List<JdbcJoinCondition>,
        rightAssignments: Map<JdbcColumnHandle, String>,
        leftAssignments: Map<JdbcColumnHandle, String>,
        statistics: JoinStatistics,
    ): Optional<PreparedQuery> {
        if (joinType != JoinType.INNER) {
            return Optional.empty()
        }
        return JdbcJoinPushdownUtil.implementJoinCostAware(session, joinType, leftSource, rightSource, statistics) {
            super.legacyImplementJoin(session, joinType, leftSource, rightSource, joinConditions, rightAssignments, leftAssignments, statistics)
        }
    }

    /**
     * Complex (expression-based) join path: deliberately NOT implemented. It is force-off by
     * default ([DorisClientModule]); if a user re-enables `complex_join_pushdown_enabled`,
     * conditions still cannot convert (no variable-comparison rules), so joins simply stay
     * local — this override just makes the posture explicit and future-proof.
     */
    override fun implementJoin(
        session: ConnectorSession,
        joinType: JoinType,
        leftSource: PreparedQuery,
        leftProjections: Map<JdbcColumnHandle, String>,
        rightSource: PreparedQuery,
        rightProjections: Map<JdbcColumnHandle, String>,
        joinConditions: List<ParameterizedExpression>,
        statistics: JoinStatistics,
    ): Optional<PreparedQuery> = Optional.empty()

    /**
     * Per-condition typed guard (legacy path). Operators: `=` and IDENTICAL (Trino
     * `IS NOT DISTINCT FROM` -> Doris `<=>`, truth-table live-proven identical incl.
     * NULL-key matching in joins) plus the four range comparisons and `<>` — all probed
     * NULL-drop-identical on both engines. Key types: the exact NON-TEXT set only —
     * integers, DECIMAL (incl. the LARGEINT mapping), DATE, DATETIME, BOOLEAN.
     * Excluded with reasons (dev-docs/archive/NOTES-p5-joins.md):
     * - CHAR/VARCHAR keys: collation posture is mode-gated for filters; join conditions
     *   compare remote-stored bytes vs Trino-wire values — deferred even for BINARY/FULL
     *   modes until proven (documented future extension; byte semantics themselves are
     *   proven by the P4 probe).
     * - FLOAT/DOUBLE keys: the wire text of extreme doubles reads "Infinity" (P0/P5-stats
     *   evidence), so Trino-side and Doris-side comparisons can see DIFFERENT values —
     *   a remote join could return different rows than the local join it replaces.
     * - IPADDRESS/JSON/etc.: unproven or non-comparable.
     */
    override fun isSupportedJoinCondition(session: ConnectorSession, joinCondition: JdbcJoinCondition): Boolean =
        isJoinKeyTypeSupported(joinCondition.leftColumn.columnType) &&
            isJoinKeyTypeSupported(joinCondition.rightColumn.columnType)

    private fun isJoinKeyTypeSupported(type: Type): Boolean = when (type) {
        is TinyintType, is SmallintType, is IntegerType, is BigintType,
        is DecimalType, is DateType, is TimestampType, is BooleanType,
        -> true
        else -> false
    }

    /**
     * Remote statistics for the cost-based optimizer (PLAN P4 tail; probe verdicts in
     * `dev-docs/archive/NOTES-p5-statistics.md`). Config-gated (`doris.statistics.enabled` /
     * `statistics_enabled`). FAIL-SOFT by contract: statistics are advisory, so any failure
     * degrades to [TableStatistics.empty] (all-unknown) with a DEBUG log — never a query
     * failure. The connector never issues ANALYZE (that writes to Doris's stats store);
     * auto-analyze and manual ANALYZE are the user's domain.
     */
    @Suppress("TooGenericExceptionCaught") // fail-soft BY CONTRACT: stats are advisory, any failure degrades to unknown
    override fun getTableStatistics(session: ConnectorSession, handle: JdbcTableHandle): TableStatistics {
        if (!DorisSessionProperties.isStatisticsEnabled(session) || !handle.isNamedRelation) {
            return TableStatistics.empty()
        }
        return try {
            val remoteTableName = handle.requiredNamedRelation.remoteTableName
            val remoteSchema = remoteSchemaName(remoteTableName)
            val columns = handle.columns.orElseGet {
                getColumns(session, handle.requiredNamedRelation.schemaTableName, remoteTableName)
            }
            connectionFactory.openConnection(session).use { connection ->
                statisticsReader.readStatistics(connection, remoteSchema, remoteTableName.tableName, columns)
            }
        } catch (e: Exception) {
            log.debug(e, "statistics unavailable for %s — returning unknown estimates", handle)
            TableStatistics.empty()
        }
    }

    override fun getColumns(
        session: ConnectorSession,
        schemaTableName: SchemaTableName,
        remoteTableName: RemoteTableName,
    ): List<JdbcColumnHandle> {
        // Metadata truth = information_schema.columns.COLUMN_TYPE (ledger global rule 2).
        // TABLE_SCHEMA in Doris information_schema is the database, i.e. the JDBC catalog.
        val remoteSchema = remoteSchemaName(remoteTableName)
        try {
            connectionFactory.openConnection(session).use { connection ->
                connection.prepareStatement(COLUMNS_QUERY).use { statement ->
                    statement.setString(1, remoteSchema)
                    statement.setString(2, remoteTableName.tableName)
                    statement.executeQuery().use { resultSet ->
                        return readColumns(session, schemaTableName, resultSet)
                    }
                }
            }
        } catch (e: SQLException) {
            throw TrinoException(JDBC_ERROR, e)
        }
    }

    private fun remoteSchemaName(remoteTableName: RemoteTableName): String =
        remoteTableName.catalogName
            .or { remoteTableName.schemaName }
            .orElseThrow { TrinoException(JDBC_ERROR, "Remote table has no schema/catalog: $remoteTableName") }

    private fun readColumns(session: ConnectorSession, schemaTableName: SchemaTableName, resultSet: ResultSet): List<JdbcColumnHandle> {
        val columns = ImmutableList.builder<JdbcColumnHandle>()
        var allColumns = 0
        var supportedColumns = 0
        while (resultSet.next()) {
            allColumns++
            val columnName = resultSet.getString("COLUMN_NAME")
            val columnType = resultSet.getString("COLUMN_TYPE")
            val nullable = resultSet.getString("IS_NULLABLE").equals("YES", ignoreCase = true)
            val comment = Optional.ofNullable(resultSet.getString("COLUMN_COMMENT")).filter { it.isNotEmpty() }
            val typeHandle = DorisTypeMapping.toTypeHandle(columnType)
            val mapping = typeMapping.toColumnMapping(session, columnType)
            if (mapping.isPresent) {
                supportedColumns++
                columns.add(
                    JdbcColumnHandle.builder()
                        .setColumnName(columnName)
                        .setJdbcTypeHandle(typeHandle)
                        .setColumnType(mapping.get().type)
                        .setNullable(nullable)
                        .setComment(comment)
                        .build(),
                )
            }
            // Unsupported types (ARRAY until P2, MAP/STRUCT, BITMAP/HLL/AGG_STATE, DECIMAL>38
            // handled inside the mapper) follow the v1 unsupported-type policy: hidden.
        }
        if (allColumns == 0) {
            throw TableNotFoundException(schemaTableName)
        }
        if (supportedColumns == 0) {
            throw TableNotFoundException(
                schemaTableName,
                "Table '$schemaTableName' has no supported columns (all $allColumns columns are not supported)",
            )
        }
        return columns.build()
    }

    override fun toColumnMapping(
        session: ConnectorSession,
        connection: Connection,
        typeHandle: JdbcTypeHandle,
    ): Optional<ColumnMapping> {
        val columnType = typeHandle.jdbcTypeName()
            .orElseThrow { TrinoException(JDBC_ERROR, "Doris COLUMN_TYPE is missing from type handle: $typeHandle") }
        return typeMapping.toColumnMapping(session, columnType)
    }

    override fun toWriteMapping(session: ConnectorSession, type: Type): WriteMapping {
        // Read-only connector (PLAN §7): no Trino->Doris type write path exists. The full
        // defense-in-depth read-only enforcement (ForwardingJdbcClient wrapper, access
        // control, system.execute denial) lands in P1b.
        throw TrinoException(NOT_SUPPORTED, "This connector does not support writes")
    }

    override fun buildSql(
        session: ConnectorSession,
        connection: Connection,
        split: JdbcSplit,
        table: JdbcTableHandle,
        columns: List<JdbcColumnHandle>,
    ): PreparedStatement {
        // Per-query timeout applied SERVER-side at session scope on the scan connection.
        // Live-probed on 4.1.3: the Doris timeout checker kills a still-running (including
        // send-blocked/backpressured) query — "query is timeout, killed by timeout checker" —
        // whereas Connector/J's client-side Statement.setQueryTimeout timer does NOT cover the
        // streaming drain phase and never fires there (P1b probe; PROBE §8/§9 for the SET
        // mechanism). Session scope only, never global (ledger §B).
        DorisSessionProperties.getQueryTimeout(session)?.let { timeout ->
            val seconds = DorisTimeouts.querySeconds(timeout)
            connection.createStatement().use { it.execute("SET query_timeout = $seconds") }
        }
        // Fresh per execution attempt: early close of this scan must never kill sibling scans.
        val marker = "trino_statement_id=${UUID.randomUUID()}"
        val query = prepareQuery(session, connection, table, Optional.empty(), columns, emptyMap(), Optional.of(split))
            .transformQuery { sql -> "/*$marker*/ $sql" }
        val statement = queryBuilder.prepareStatement(this, session, connection, query, Optional.of(columns.size))
        return DorisPreparedStatement(statement, connection) { executionFinished ->
            clusterScopedCancel.cancel(session, marker, executionFinished)
        }
    }

    override fun getPreparedStatement(connection: Connection, sql: String, columnCount: Optional<Int>): PreparedStatement {
        // Streaming result mode == setFetchSize(Integer.MIN_VALUE): ~2.4x lower peak heap on a
        // 1M-row scan, full scan under -Xmx256m (PROBE §7; ledger §B "streaming"; PLAN G8).
        val statement = connection.prepareStatement(sql)
        if (statement.isWrapperFor(JdbcStatement::class.java)) {
            statement.unwrap(JdbcStatement::class.java).enableStreamingResults()
        }
        return statement
    }

    override fun abortReadConnection(connection: Connection, resultSet: ResultSet) {
        if (!resultSet.isAfterLast) {
            // Scan cancellation already ran through DorisPreparedStatement.cancel(); retain
            // the socket-only fallback for other BaseJdbcClient read paths too.
            connection.abort(directExecutor())
        }
    }

    // LIMIT pushdown is inherited-and-preserved baseline behavior: stock Trino 483 pushed
    // LIMIT to Doris 4.1.3 as a single remote statement (STOCK pushdown ledger; ledger §E).
    override fun limitFunction(): Optional<BiFunction<String, Long, String>> {
        return Optional.of(BiFunction { sql, limit -> "$sql LIMIT $limit" })
    }

    override fun isLimitGuaranteed(session: ConnectorSession): Boolean = true

    /**
     * Safe TopN (PLAN §6.5; P3): every key must have proven ordering semantics, including
     * IPADDRESS and mode-gated native VARCHAR. CHAR, REAL/DOUBLE, and text-rendered complex
     * types stay local because stored-value and exposed-value ordering can differ.
     * Doris 4.1.3 supports the native `NULLS FIRST/LAST` syntax
     * for all four Trino orderings (live-probed 2026-07-19 — SR K6's ISNULL-prefix trick is
     * unnecessary; "keep the pattern, re-decide the rendering" resolved to native). A TopN is
     * always rendered WITH its LIMIT (bare ORDER BY is never emitted; probed anyway:
     * `default_order_by_limit=-1`, no 65535 truncation, LIMIT 70000 returns exactly 70000
     * ordered rows).
     */
    override fun supportsTopN(session: ConnectorSession, handle: JdbcTableHandle, sortOrder: List<JdbcSortItem>): Boolean =
        sortOrder.all { !isDecimal256(it.column()) && isPushableSortKey(session, it.column()) }

    /**
     * Native VARCHAR sort keys become TopN-eligible in BINARY/FULL string-pushdown mode: Doris
     * `ORDER BY` over strings is pure byte order (probe: the full adversarial set sorts in
     * ascending UTF-8 byte order), which equals Trino's VARCHAR codepoint ordering. CHAR keys
     * stay OFF in every mode — Trino orders trimmed/padded CHAR values while Doris orders
     * stored bytes, divergent for trailing-space data and undetectable from the query
     * (dev-docs/archive/REPORT-string-comparison-probe-4.1.3.md "ORDER BY").
     */
    private fun isPushableSortKey(session: ConnectorSession, column: JdbcColumnHandle): Boolean = when {
        column.columnType is VarcharType -> DorisColumnType.isNativeString(column) &&
            DorisSessionProperties.getStringPushdownMode(session).allowsFullStringPushdown
        // IPADDRESS: Doris IPV4/IPV6 order == Trino's unsigned big-endian 16-byte order
        // (IpAddressType.comparisonOperator), live-proven byte-exact; NULLS FIRST/LAST native.
        typeMapping.isIpAddress(column.columnType) -> true
        else -> isPushableSortKey(column.columnType)
    }

    override fun topNFunction(): Optional<BaseJdbcClient.TopNFunction> {
        return Optional.of(
            BaseJdbcClient.TopNFunction { query, sortItems, limit ->
                val orderBy = sortItems.joinToString(", ") { sortItem ->
                    "${quoted(sortItem.column().columnName)} ${ORDERINGS.getValue(sortItem.sortOrder())}"
                }
                "$query ORDER BY $orderBy LIMIT $limit"
            },
        )
    }

    // Doris ORDER BY + LIMIT is exact (full sort, exact limit incl. >65535 — live-probed),
    // so the engine may drop its local TopN entirely.
    override fun isTopNGuaranteed(session: ConnectorSession): Boolean = true

    override fun convertProjection(
        session: ConnectorSession,
        handle: JdbcTableHandle,
        expression: ConnectorExpression,
        assignments: Map<String, ColumnHandle>,
    ): Optional<JdbcExpression> {
        val rewritten = rewriters.projection.rewrite(session, handle, expression, assignments)
        logPushdown("projection pushdown", "unsupported shape", expression, rewritten.map { it.expression })
        return rewritten
    }

    override fun implementAggregation(
        session: ConnectorSession,
        aggregate: AggregateFunction,
        assignments: Map<String, ColumnHandle>,
    ): Optional<JdbcExpression> {
        val rewritten = rewriters.aggregate.rewrite(session, aggregate, assignments)
        logPushdown("aggregate pushdown", "no verified family", aggregate, rewritten.map { it.expression })
        return rewritten
    }

    private fun logPushdown(kind: String, rejectionReason: String, subject: Any, sql: Optional<String>) {
        if (sql.isPresent) {
            log.debug("%s accepted: %s -> %s", kind, subject, sql.get())
        } else {
            log.debug("%s rejected (%s): %s", kind, rejectionReason, subject)
        }
    }

    /**
     * GROUP BY comes with aggregate pushdown, so every grouping key must be either an
     * exact-pushable type ([DorisAggregatePushdown.isExactPushableKeyType]) or — since
     * READ-ONLY-MAX batch 3 — native VARCHAR/STRING: grouping is EQUALITY-only, and string
     * equality is byte-exact on both engines (P4 probe; the tiered-GUARDED evidence),
     * with wire fidelity proven for the key values that come back. This also makes
     * SELECT DISTINCT over text columns push (DISTINCT lowers to an aggregation with no
     * aggregate functions). CHAR keys stay excluded (Doris groups stored bytes, Trino
     * groups trimmed values — the standing trailing-space divergence); REAL/DOUBLE stay
     * excluded (NaN/±0.0 grouping hazard). Ordering-dependent surfaces (min/max args,
     * count DISTINCT args, TopN sort keys) deliberately do NOT inherit this widening —
     * those remain governed by their own gates and the string-mode design.
     * NULL grouping semantics are live-proven identical (NULL keys form one group).
     * Complex types exposed as VARCHAR cannot inherit native string grouping semantics.
     */
    override fun supportsAggregationPushdown(
        session: ConnectorSession,
        table: JdbcTableHandle,
        aggregates: List<AggregateFunction>,
        assignments: Map<String, ColumnHandle>,
        groupingSets: List<List<ColumnHandle>>,
    ): Boolean {
        val unsupportedKey = groupingSets.flatten()
            .map { it as JdbcColumnHandle }
            .firstOrNull {
                isDecimal256(it) ||
                    (!DorisAggregatePushdown.isExactPushableKeyType(it.columnType) &&
                        !DorisColumnType.isNativeString(it))
            }
        if (unsupportedKey != null) {
            log.debug(
                "aggregate pushdown rejected (grouping key type not exact-pushable: %s %s)",
                unsupportedKey.columnName,
                unsupportedKey.columnType,
            )
            return false
        }
        return true
    }

    private fun isDecimal256(column: JdbcColumnHandle): Boolean =
        column.jdbcTypeHandle.jdbcTypeName()
            .map(DorisTypeMapping::isDecimal256)
            .orElse(false)

    companion object {
        private val log = Logger.get(DorisClient::class.java)

        private val HIDDEN_SCHEMAS = setOf("information_schema", "mysql", "__internal_schema")

        /** Native Doris NULLS placement for all four Trino orderings (live-proven identical). */
        private val ORDERINGS: Map<SortOrder, String> = mapOf(
            SortOrder.ASC_NULLS_FIRST to "ASC NULLS FIRST",
            SortOrder.ASC_NULLS_LAST to "ASC NULLS LAST",
            SortOrder.DESC_NULLS_FIRST to "DESC NULLS FIRST",
            SortOrder.DESC_NULLS_LAST to "DESC NULLS LAST",
        )

        /**
         * Non-text exact types only (KDoc on [supportsTopN] carries the exclusion evidence).
         * The SAME identity evidence covers min/max arguments and GROUP BY keys, so P4 shares
         * the set ([DorisAggregatePushdown.isExactPushableKeyType]).
         */
        private fun isPushableSortKey(type: Type): Boolean = DorisAggregatePushdown.isExactPushableKeyType(type)

        private const val COLUMNS_QUERY = """
            SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
            FROM information_schema.columns
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
        """
    }
}
