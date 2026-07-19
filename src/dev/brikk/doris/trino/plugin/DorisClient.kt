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
package dev.brikk.doris.trino.plugin

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import com.google.inject.Inject
import com.mysql.cj.jdbc.JdbcStatement
import io.trino.plugin.base.mapping.IdentifierMapping
import io.trino.plugin.jdbc.BaseJdbcClient
import io.trino.plugin.jdbc.BaseJdbcConfig
import io.trino.plugin.jdbc.ColumnMapping
import io.trino.plugin.jdbc.ConnectionFactory
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcErrorCode.JDBC_ERROR
import io.trino.plugin.jdbc.JdbcSortItem
import io.trino.plugin.jdbc.JdbcSplit
import io.trino.plugin.jdbc.JdbcTableHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.plugin.jdbc.QueryBuilder
import io.trino.plugin.jdbc.RemoteTableName
import io.trino.plugin.jdbc.WriteMapping
import io.trino.plugin.jdbc.logging.RemoteQueryModifier
import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import io.trino.spi.connector.AggregateFunction
import io.trino.spi.connector.ColumnHandle
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.connector.SchemaTableName
import io.trino.spi.connector.TableNotFoundException
import io.trino.spi.type.Type
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
        // SR K11: abort instead of draining a streaming result on cancellation; Statement.cancel()
        // KILLs the Doris query in ~200ms (PROBE §8; ledger §D).
        if (!resultSet.isAfterLast) {
            connection.abort(directExecutor())
        }
    }

    // LIMIT pushdown is inherited-and-preserved baseline behavior: stock Trino 483 pushed
    // LIMIT to Doris 4.1.3 as a single remote statement (STOCK pushdown ledger; ledger §E).
    override fun limitFunction(): Optional<BiFunction<String, Long, String>> {
        return Optional.of(BiFunction { sql, limit -> "$sql LIMIT $limit" })
    }

    override fun isLimitGuaranteed(session: ConnectorSession): Boolean = true

    // P1a scope: no TopN (NULL-ordering rendering on 4.1.3 not yet decided, SR K6), no
    // aggregate pushdown (P4), no expression pushdown beyond exact domains (PLAN §10 P1).
    override fun supportsTopN(session: ConnectorSession, handle: JdbcTableHandle, sortOrder: List<JdbcSortItem>): Boolean = false

    override fun supportsAggregationPushdown(
        session: ConnectorSession,
        table: JdbcTableHandle,
        aggregates: List<AggregateFunction>,
        assignments: Map<String, ColumnHandle>,
        groupingSets: List<List<ColumnHandle>>,
    ): Boolean = false

    companion object {
        private val HIDDEN_SCHEMAS = setOf("information_schema", "mysql", "__internal_schema")

        private const val COLUMNS_QUERY = """
            SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
            FROM information_schema.columns
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
        """
    }
}
