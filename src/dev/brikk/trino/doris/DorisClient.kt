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
 * Doris databases appear as JDBC catalogs, so `TABLE_CAT` is the remote schema name. Column types
 * come from `information_schema.columns.COLUMN_TYPE` because `DatabaseMetaData.getColumns`
 * narrows LARGEINT to INT, reports IPV4/IPV6/VARIANT as OTHER and BITMAP as BIT, and gives only
 * the element type of an ARRAY.
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
    // Scans get the query-id comment from DorisQueryBuilder; this covers the other statement paths.
    DorisRemoteQueryModifier(queryModifier),
    false,
) {
    private val typeMapping = DorisTypeMapping(typeManager)

    internal val clusterScopedCancel = DorisClusterScopedCancel(cancelConnectionFactory, dorisConfig.isClusterScopedCancel())

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
        return schemaName.lowercase(Locale.ENGLISH) !in HIDDEN_SCHEMAS
    }

    override fun getTables(connection: Connection, schemaName: Optional<String>, tableName: Optional<String>): ResultSet {
        // The schema is passed as the JDBC catalog.
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
     * Only inner joins are pushed. On the null-supplying side of an outer join a column's NOT NULL
     * flag is no longer true, and rules such as min_by/max_by rely on it.
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

    /** Complex join pushdown is off by default ([DorisClientModule]); this keeps joins local if a session enables it. */
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
     * Join keys are limited to exact non-text types. Text keys stay local in every string mode:
     * CHAR compares stored bytes in Doris but ignores trailing spaces in Trino, and VARCHAR join
     * keys are unverified. REAL/DOUBLE keys stay local because extreme values read back as
     * Infinity, so the engines would compare different values.
     */
    override fun isSupportedJoinCondition(session: ConnectorSession, joinCondition: JdbcJoinCondition): Boolean =
        DorisAggregatePushdown.isExactPushableKeyType(joinCondition.leftColumn.columnType) &&
            DorisAggregatePushdown.isExactPushableKeyType(joinCondition.rightColumn.columnType)

    @Suppress("TooGenericExceptionCaught") // statistics are advisory: any failure degrades to unknown estimates
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
        // TABLE_SCHEMA holds the Doris database, which is the JDBC catalog.
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
        throw TrinoException(NOT_SUPPORTED, "This connector does not support writes")
    }

    override fun buildSql(
        session: ConnectorSession,
        connection: Connection,
        split: JdbcSplit,
        table: JdbcTableHandle,
        columns: List<JdbcColumnHandle>,
    ): PreparedStatement {
        // Server-side, because Connector/J's Statement.setQueryTimeout does not fire while a
        // streaming result is drained; Doris's query_timeout also stops a query blocked on sending.
        DorisSessionProperties.getQueryTimeout(session)?.let { timeout ->
            val seconds = DorisTimeouts.querySeconds(timeout)
            connection.createStatement().use { it.execute("SET query_timeout = $seconds") }
        }
        // One marker per statement: Trino closes scans early, for LIMIT too, and cancelling one
        // must not kill its sibling scans.
        val marker = "trino_statement_id=${UUID.randomUUID()}"
        val query = prepareQuery(session, connection, table, Optional.empty(), columns, emptyMap(), Optional.of(split))
            .transformQuery { sql -> "/*$marker*/ $sql" }
        val statement = queryBuilder.prepareStatement(this, session, connection, query, Optional.of(columns.size))
        return DorisPreparedStatement(statement, connection) { executionFinished ->
            clusterScopedCancel.cancel(session, marker, executionFinished)
        }
    }

    override fun getPreparedStatement(connection: Connection, sql: String, columnCount: Optional<Int>): PreparedStatement {
        // Otherwise Connector/J buffers the whole result set in memory.
        val statement = connection.prepareStatement(sql)
        if (statement.isWrapperFor(JdbcStatement::class.java)) {
            statement.unwrap(JdbcStatement::class.java).enableStreamingResults()
        }
        return statement
    }

    override fun abortReadConnection(connection: Connection, resultSet: ResultSet) {
        if (!resultSet.isAfterLast) {
            // Scans are cancelled by DorisPreparedStatement; this covers the other read paths.
            connection.abort(directExecutor())
        }
    }

    override fun limitFunction(): Optional<BiFunction<String, Long, String>> {
        return Optional.of(BiFunction { sql, limit -> "$sql LIMIT $limit" })
    }

    override fun isLimitGuaranteed(session: ConnectorSession): Boolean = true

    override fun supportsTopN(session: ConnectorSession, handle: JdbcTableHandle, sortOrder: List<JdbcSortItem>): Boolean =
        sortOrder.all { isPushableTopNKey(session, it.column()) }

    /**
     * Sort keys must order identically on both engines. Doris orders strings by UTF-8 bytes, which
     * matches Trino's VARCHAR order; native VARCHAR keys push in BINARY/FULL mode. CHAR keys never
     * push: Doris orders stored bytes, while Trino ignores trailing spaces.
     */
    private fun isPushableTopNKey(session: ConnectorSession, column: JdbcColumnHandle): Boolean = when {
        column.columnType is VarcharType -> DorisColumnType.isNativeString(column) &&
            DorisSessionProperties.getStringPushdownMode(session).allowsFullStringPushdown
        // Doris IPV4/IPV6 order matches Trino's unsigned 16-byte IPADDRESS order.
        typeMapping.isIpAddress(column.columnType) -> true
        else -> DorisAggregatePushdown.isExactPushableKeyType(column.columnType)
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

    // Doris sorts fully and applies the exact LIMIT, including limits above 65535.
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
     * Grouping keys must be equal in Doris exactly when they are equal in Trino: exact numeric,
     * temporal and boolean types, plus native VARCHAR/STRING (byte-equal on both engines).
     * CHAR (trailing spaces), REAL/DOUBLE (NaN, -0.0) and types exposed as text (Decimal256,
     * complex types) stay local.
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
                !DorisAggregatePushdown.isExactPushableKeyType(it.columnType) && !DorisColumnType.isNativeString(it)
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

        private val HIDDEN_SCHEMAS = setOf("information_schema", "mysql", "__internal_schema")

        private val ORDERINGS: Map<SortOrder, String> = mapOf(
            SortOrder.ASC_NULLS_FIRST to "ASC NULLS FIRST",
            SortOrder.ASC_NULLS_LAST to "ASC NULLS LAST",
            SortOrder.DESC_NULLS_FIRST to "DESC NULLS FIRST",
            SortOrder.DESC_NULLS_LAST to "DESC NULLS LAST",
        )

        private const val COLUMNS_QUERY = """
            SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
            FROM information_schema.columns
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
        """
    }
}
