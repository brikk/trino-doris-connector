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
import io.trino.plugin.base.aggregation.AggregateFunctionRewriter
import io.trino.plugin.base.expression.ConnectorExpressionRewriter
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
import io.trino.plugin.jdbc.aggregation.ImplementCount
import io.trino.plugin.jdbc.aggregation.ImplementCountAll
import io.trino.plugin.jdbc.expression.JdbcConnectorExpressionRewriterBuilder
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.plugin.jdbc.expression.RewriteVariable
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
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Locale
import java.util.Optional
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
    private val clusterScopedCancel = DorisClusterScopedCancel(connectionFactory, dorisConfig.isClusterScopedCancel())

    /**
     * Typed ARRAY predicate rules (PLAN §6.1 layer 2, §6.2; P2b/P3) in two tiers:
     *
     * - VALUE-SAFE tier: `array_position` comparisons (value-identical on both engines,
     *   [DorisPushdownEvidence.ARRAY_POSITION]) plus NOT/AND/OR composition over that tier
     *   only — safe because Doris's three-valued logic is live-proven identical to Trino's
     *   (see [DorisValueSafeRewriter] and the P3 composition pins).
     * - PREDICATE-LEVEL tier: `contains` / `arrays_overlap`, whose NULL cells differ at
     *   value level (Trino NULL vs Doris 0/1). They may ONLY surface as top-level WHERE
     *   conjuncts and are structurally excluded from composition: the composition rules
     *   rewrite children through the value-safe rewriter, which does not contain them.
     *
     * Deliberately NO `addStandardRules`: scalar column-vs-literal comparisons and
     * IS [NOT] NULL are already covered exactly by domain pushdown (P1a controllers);
     * column-to-column scalar comparison rules would be new unproven surface (P3 remainder).
     * Evidence citations: [DorisPushdownEvidence].
     */
    /**
     * Batch-1 SCALAR VALUE tier ([DorisScalarPushdownRules]): typed, composable rewrites of
     * coalesce/nullif/year..second/lower/upper/length over the closed value-type set.
     * Shared by the predicate bridges below and the projection path.
     */
    private val scalarRewriterHolder = DorisScalarRewriterHolder().also { holder ->
        holder.rewriter = JdbcConnectorExpressionRewriterBuilder.newBuilder()
            .add(RewriteScalarVariable(::quoted))
            .add(RewriteScalarConstant())
            .add(RewriteScalarFunctionByName(io.trino.spi.expression.StandardFunctions.COALESCE_FUNCTION_NAME, "coalesce", 2..MAX_SCALAR_ARITY))
            .add(RewriteScalarFunctionByName(io.trino.spi.expression.StandardFunctions.NULLIF_FUNCTION_NAME, "nullif", 2..2))
            .apply { listOf("year", "month", "day", "hour", "minute", "second").forEach { add(RewriteTemporalExtract(it)) } }
            .add(RewriteCaseFold("lower"))
            .add(RewriteCaseFold("upper"))
            .add(RewriteCharLength())
            .build()
    }

    private val connectorExpressionRewriter: ConnectorExpressionRewriter<ParameterizedExpression> = run {
        val support = DorisArrayPushdownSupport(typeMapping, ::quoted)
        val valueSafe = DorisValueSafeRewriter()
        val valueSafeRules = listOf(
            RewriteArrayPositionComparison(support),
            // cardinality -> array_size: value-identical on every reachable cell (P5 pins),
            // so composable — [DorisPushdownEvidence.CARDINALITY]
            RewriteCardinalityComparison(support),
            RewriteValueSafeNot(valueSafe),
            RewriteValueSafeLogical(valueSafe),
        )
        valueSafe.rewriter = JdbcConnectorExpressionRewriterBuilder.newBuilder()
            .apply { valueSafeRules.forEach { add(it) } }
            .build()
        JdbcConnectorExpressionRewriterBuilder.newBuilder()
            .apply { valueSafeRules.forEach { add(it) } }
            .add(RewriteContains(support))
            .add(RewriteArraysOverlap(support))
            // LIKE pushes only in BINARY/FULL string mode (rule.isEnabled gates per session);
            // stays out of the value-safe tier (no composition) — probe report, LIKE section.
            .add(RewriteStringLike(::quoted))
            // JSON equality is PREDICATE-level (divergent cells are both-drop only under a
            // top-level '='), so it too stays out of the value-safe tier —
            // [DorisPushdownEvidence.JSON_EXTRACT_SCALAR]
            .add(RewriteJsonExtractScalarEquality(::quoted))
            // Batch-1 scalar bridges: comparisons / IS NULL over scalar-tier function shapes
            // (plain column shapes stay domain territory) + starts_with -> escaped LIKE-prefix.
            // Top-level-conjunct tier (not composable under NOT/AND/OR).
            .add(RewriteScalarComparisonBridge(scalarRewriterHolder))
            .add(RewriteScalarIsNullBridge(scalarRewriterHolder))
            .add(RewriteStartsWith(::quoted))
            .build()
    }

    override fun convertPredicate(
        session: ConnectorSession,
        expression: ConnectorExpression,
        assignments: Map<String, ColumnHandle>,
    ): Optional<ParameterizedExpression> {
        val rewritten = connectorExpressionRewriter.rewrite(session, expression, assignments)
        if (log.isDebugEnabled) {
            if (rewritten.isPresent) {
                log.debug("pushdown accepted: %s -> %s", expression, rewritten.get().expression())
            } else {
                log.debug("pushdown rejected (unsupported shape): %s", expression)
            }
        }
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

    private val statisticsReader = DorisTableStatisticsReader(::quoted)

    /**
     * P5 cost-aware join pushdown (PLAN §6.5; OFF by default via `join-pushdown.enabled`).
     * Joins arrive on the LEGACY path only (complex join pushdown is force-disabled in
     * [DorisClientModule] — no variable-comparison rewrite rules exist). Shape gates:
     * - INNER / LEFT / RIGHT only; FULL OUTER excluded (unproven against Doris's nereids
     *   planner for the subquery-join shape — conservative v1 per plan).
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
        if (joinType == JoinType.FULL_OUTER) {
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
     * Excluded with reasons (NOTES-p5-joins.md):
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
     * `dev-docs/NOTES-p5-statistics.md`). Config-gated (`doris.statistics.enabled` /
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
            val remoteSchema = remoteTableName.catalogName
                .or { remoteTableName.schemaName }
                .orElseThrow { TrinoException(JDBC_ERROR, "Remote table has no schema/catalog: $remoteTableName") }
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
        val remoteSchema = remoteTableName.catalogName
            .or { remoteTableName.schemaName }
            .orElseThrow { TrinoException(JDBC_ERROR, "Remote table has no schema/catalog: $remoteTableName") }
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
            val seconds = maxOf(1L, timeout.roundTo(java.util.concurrent.TimeUnit.SECONDS))
            connection.createStatement().use { it.execute("SET query_timeout = $seconds") }
        }
        // cluster-scoped cancel bookkeeping: marker+session for this scan connection, read
        // back at abort time (the busy connection itself is never touched at cancel)
        clusterScopedCancel.register(connection, session)
        return super.buildSql(session, connection, split, table, columns)
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
            // PRIMARY: async cluster-scoped kill (marker -> cluster-wide processlist ->
            // QueryId -> KILL QUERY, forwarded cross-FE) — correct behind LB/multi-FE where
            // driver-level kills target an arbitrary FE ([DorisClusterScopedCancel]).
            clusterScopedCancel.onAbort(connection)
            // BELT (stock, SR K11): abort the socket instead of draining the streaming
            // result. On the owning FE this alone releases the query; behind an LB it may
            // not — hence the primary above.
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
     * Safe TopN (PLAN §6.5; P3): pushed only when EVERY sort key is a non-text exact type.
     * Excluded with evidence: CHAR/VARCHAR (G5 — Doris collation unproven; SR K7 posture),
     * REAL/DOUBLE (approximate; Trino orders NaN largest — Doris NaN placement unproven),
     * everything else unproven. Doris 4.1.3 supports the native `NULLS FIRST/LAST` syntax
     * for all four Trino orderings (live-probed 2026-07-19 — SR K6's ISNULL-prefix trick is
     * unnecessary; "keep the pattern, re-decide the rendering" resolved to native). A TopN is
     * always rendered WITH its LIMIT (bare ORDER BY is never emitted; probed anyway:
     * `default_order_by_limit=-1`, no 65535 truncation, LIMIT 70000 returns exactly 70000
     * ordered rows).
     */
    override fun supportsTopN(session: ConnectorSession, handle: JdbcTableHandle, sortOrder: List<JdbcSortItem>): Boolean =
        sortOrder.all { isPushableSortKey(session, it.column().columnType) }

    /**
     * VARCHAR sort keys become TopN-eligible in BINARY/FULL string-pushdown mode: Doris
     * `ORDER BY` over strings is pure byte order (probe: the full adversarial set sorts in
     * ascending UTF-8 byte order), which equals Trino's VARCHAR codepoint ordering. CHAR keys
     * stay OFF in every mode — Trino orders trimmed/padded CHAR values while Doris orders
     * stored bytes, divergent for trailing-space data and undetectable from the query
     * (REPORT-string-comparison-probe-4.1.3.md "ORDER BY").
     */
    private fun isPushableSortKey(session: ConnectorSession, type: Type): Boolean = when {
        type is io.trino.spi.type.VarcharType -> DorisSessionProperties.getStringPushdownMode(session).allowsFullStringPushdown
        // IPADDRESS: Doris IPV4/IPV6 order == Trino's unsigned big-endian 16-byte order
        // (IpAddressType.comparisonOperator), live-proven byte-exact; NULLS FIRST/LAST native.
        typeMapping.isIpAddress(type) -> true
        else -> isPushableSortKey(type)
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

    /**
     * Verified aggregate pushdown (PLAN §6.5, P4), one live-proven family at a time. The
     * argument rewriter is DELIBERATELY only [RewriteVariable] (quote a column reference):
     * aggregate arguments must stay bare columns — no derived-expression surface. Family
     * verdicts (full proofs in `dev-docs/NOTES-p4-aggregates.md`, pinned by
     * `TestDorisP4AggregateProbes`):
     *
     * - `count(*)` / `count(x)`: pushed (stock rules; bigint result; empty/all-NULL proven).
     * - `count(DISTINCT x)`, exact key types: pushed ([DorisImplementCountDistinct]).
     * - `min/max`, exact key types: pushed ([DorisImplementMinMax]); text (collation) and
     *   REAL/DOUBLE (Doris max = NaN vs Trino 483 max = Infinity — divergent) stay local.
     * - `sum(DECIMAL(p<=18, s))`: pushed ([DorisImplementSum]); every other sum family stays
     *   local — Doris sums wrap SILENTLY (bigint at 2^64, decimal/largeint at 2^128) where
     *   Trino throws.
     * - `avg`: NEVER pushed. Doris avg(DECIMALV3(p, s)) computes at scale max(s, 4) with
     *   TRUNCATION (live: avg{0.0001, 0.0000} = 0.0000 vs Trino HALF_UP 0.0001 at s=4) and
     *   silently corrupts at p=38 (intermediate overflow); Doris avg(BIGINT) computes an
     *   EXACT wide integer sum (live: avg{2^53, 1, -2^53, 1} = 0.5) while Trino 483
     *   accumulates in DOUBLE (order-dependent, lossy — BigintAverageAggregations). Kept
     *   local, Trino semantics preserved.
     */
    private val aggregateFunctionRewriter: AggregateFunctionRewriter<JdbcExpression, ParameterizedExpression> = AggregateFunctionRewriter(
        JdbcConnectorExpressionRewriterBuilder.newBuilder()
            .add(RewriteVariable { name -> quoted(name) })
            .build(),
        ImmutableSet.of(
            ImplementCountAll(BIGINT_TYPE_HANDLE),
            ImplementCount(BIGINT_TYPE_HANDLE),
            DorisImplementCountDistinct(),
            DorisImplementMinMax(),
            DorisImplementSum(),
            DorisImplementMinMaxBy(),
            DorisImplementAnyValue(),
            DorisImplementApproxDistinct(),
        ),
    )

    /**
     * P6 typed scalar PROJECTION pushdown ([RewriteCastDatetimeToDate], [RewriteDateTrunc]):
     * a converted projection becomes a synthetic derived column, which the aggregate
     * machinery can then GROUP BY remotely — `GROUP BY date(event_at)` collapses into one
     * Doris statement (proven end-to-end in TestDorisP6DateProjection). The inner rewriter
     * only needs [RewriteVariable]; rules own their type/shape guards.
     */
    private val projectFunctionRewriter = io.trino.plugin.base.projection.ProjectFunctionRewriter(
        // the scalar tier doubles as the projection expression rewriter, so every scalar
        // shape (and composition) it accepts becomes a groupable synthetic column
        scalarRewriterHolder.rewriter,
        com.google.common.collect.ImmutableSet.of<io.trino.plugin.base.projection.ProjectFunctionRule<JdbcExpression, ParameterizedExpression>>(
            RewriteCastDatetimeToDate(::quoted),
            RewriteDateTrunc(::quoted),
            RewriteScalarProjection(scalarRewriterHolder),
        ),
    )

    override fun convertProjection(
        session: ConnectorSession,
        handle: JdbcTableHandle,
        expression: ConnectorExpression,
        assignments: Map<String, ColumnHandle>,
    ): Optional<JdbcExpression> {
        val rewritten = projectFunctionRewriter.rewrite(session, handle, expression, assignments)
        if (log.isDebugEnabled) {
            if (rewritten.isPresent) {
                log.debug("projection pushdown accepted: %s -> %s", expression, rewritten.get().expression)
            } else {
                log.debug("projection pushdown rejected (unsupported shape): %s", expression)
            }
        }
        return rewritten
    }

    override fun implementAggregation(
        session: ConnectorSession,
        aggregate: AggregateFunction,
        assignments: Map<String, ColumnHandle>,
    ): Optional<JdbcExpression> {
        val rewritten = aggregateFunctionRewriter.rewrite(session, aggregate, assignments)
        if (log.isDebugEnabled) {
            if (rewritten.isPresent) {
                log.debug("aggregate pushdown accepted: %s -> %s", aggregate, rewritten.get().expression)
            } else {
                log.debug("aggregate pushdown rejected (no verified family): %s", aggregate)
            }
        }
        return rewritten
    }

    /**
     * GROUP BY comes with aggregate pushdown, so every grouping key must be either an
     * exact-pushable type ([DorisAggregatePushdown.isExactPushableKeyType]) or — since
     * READ-ONLY-MAX batch 3 — VARCHAR/STRING: grouping is EQUALITY-only, and string
     * equality is byte-exact on both engines (P4 probe; the tiered-GUARDED evidence),
     * with wire fidelity proven for the key values that come back. This also makes
     * SELECT DISTINCT over text columns push (DISTINCT lowers to an aggregation with no
     * aggregate functions). CHAR keys stay excluded (Doris groups stored bytes, Trino
     * groups trimmed values — the standing trailing-space divergence); REAL/DOUBLE stay
     * excluded (NaN/±0.0 grouping hazard). Ordering-dependent surfaces (min/max args,
     * count DISTINCT args, TopN sort keys) deliberately do NOT inherit this widening —
     * those remain governed by their own gates and the string-mode design.
     * NULL grouping semantics are live-proven identical (NULL keys form one group).
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
                !DorisAggregatePushdown.isExactPushableKeyType(it.columnType) &&
                    it.columnType !is io.trino.spi.type.VarcharType
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

    companion object {
        private val log = Logger.get(DorisClient::class.java)

        private const val MAX_SCALAR_ARITY = 10

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

        /** Synthesized result handle for remote BIGINT aggregate results (count family). */
        private val BIGINT_TYPE_HANDLE: JdbcTypeHandle = DorisTypeMapping.toTypeHandle("bigint")

        private const val COLUMNS_QUERY = """
            SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
            FROM information_schema.columns
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
        """
    }
}
