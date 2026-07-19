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

import com.google.inject.Inject
import io.trino.plugin.jdbc.ForwardingJdbcClient
import io.trino.plugin.jdbc.JdbcClient
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcMergeTableHandle
import io.trino.plugin.jdbc.JdbcOutputTableHandle
import io.trino.plugin.jdbc.JdbcTableHandle
import io.trino.plugin.jdbc.RemoteTableName
import io.trino.plugin.jdbc.WriteFunction
import io.trino.plugin.jdbc.WriteMapping
import io.trino.spi.StandardErrorCode.NOT_SUPPORTED
import io.trino.spi.TrinoException
import io.trino.spi.connector.ColumnHandle
import io.trino.spi.connector.ColumnMetadata
import io.trino.spi.connector.ColumnPosition
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.connector.ConnectorTableMetadata
import io.trino.spi.connector.RetryMode
import io.trino.spi.connector.SchemaTableName
import io.trino.spi.type.Type
import java.sql.Connection
import java.util.Optional
import java.util.OptionalInt
import java.util.OptionalLong
import java.util.function.Consumer

/**
 * Read-only guard layer (PLAN G7.2): rejects EVERY mutating [JdbcClient] method before any
 * remote SQL can be generated. This is one layer of the defense in depth — connector access
 * control ([DorisReadOnlyAccessControl]) denies at the engine boundary, and the SELECT-only
 * Doris account denies at the server (PLAN G7.1/G7.3; ledger §C).
 *
 * Two overrides here are the `system.execute` choke point (PLAN G7.5): Base JDBC's
 * auto-installed `ExecuteProcedure` does NOT call [JdbcClient.execute] — it opens
 * `jdbcClient.getConnection(session)` and runs `Statement.executeUpdate` directly (verified in
 * the 483 source). The 1-arg [getConnection] overload is called ONLY by `ExecuteProcedure` and
 * `JdbcMergeSink` (both write paths), so throwing from it (and from [execute]) makes the
 * procedure physically unable to reach Doris even if an access-control layer were bypassed.
 *
 * `TestReadOnlyDorisClient` classifies the ENTIRE 483 [JdbcClient] surface by reflection and
 * fails loud if a future base-jdbc upgrade adds a method not covered here or explicitly
 * allow-listed.
 *
 * Mutator parameters are deliberately declared nullable: the read-only rejection must win over
 * any argument validation, so the guard throws its error even for null/garbage arguments
 * (parameters are never touched).
 */
class ReadOnlyDorisClient internal constructor(
    private val delegate: JdbcClient,
) : ForwardingJdbcClient() {
    @Inject
    constructor(delegate: DorisClient) : this(delegate as JdbcClient)

    override fun delegate(): JdbcClient = delegate

    private fun readOnlyViolation(operation: String): Nothing {
        throw TrinoException(NOT_SUPPORTED, "$READ_ONLY_MESSAGE (rejected: $operation)")
    }

    // --- write type mapping / capability probes ---

    override fun toWriteMapping(session: ConnectorSession?, type: Type?): WriteMapping =
        readOnlyViolation("toWriteMapping")

    override fun supportsMerge(): Boolean = false

    override fun getMaxWriteParallelism(session: ConnectorSession?): OptionalInt =
        readOnlyViolation("getMaxWriteParallelism")

    // --- schema DDL ---

    override fun createSchema(session: ConnectorSession?, schemaName: String?): Unit =
        readOnlyViolation("createSchema")

    override fun dropSchema(session: ConnectorSession?, schemaName: String?, cascade: Boolean): Unit =
        readOnlyViolation("dropSchema")

    override fun renameSchema(session: ConnectorSession?, schemaName: String?, newSchemaName: String?): Unit =
        readOnlyViolation("renameSchema")

    // --- table DDL ---

    override fun createTable(session: ConnectorSession?, tableMetadata: ConnectorTableMetadata?): Unit =
        readOnlyViolation("createTable")

    override fun beginCreateTable(
        session: ConnectorSession?,
        tableMetadata: ConnectorTableMetadata?,
        rollbackActionCollector: Consumer<Runnable>?,
    ): JdbcOutputTableHandle = readOnlyViolation("beginCreateTable")

    override fun commitCreateTable(session: ConnectorSession?, handle: JdbcOutputTableHandle?, pageSinkIds: Set<Long>?): Unit =
        readOnlyViolation("commitCreateTable")

    override fun renameTable(session: ConnectorSession?, handle: JdbcTableHandle?, newTableName: SchemaTableName?): Unit =
        readOnlyViolation("renameTable")

    override fun dropTable(session: ConnectorSession?, jdbcTableHandle: JdbcTableHandle?): Unit =
        readOnlyViolation("dropTable")

    override fun truncateTable(session: ConnectorSession?, handle: JdbcTableHandle?): Unit =
        readOnlyViolation("truncateTable")

    override fun rollbackDestinationTableCreation(session: ConnectorSession?, remoteTableName: RemoteTableName?): Unit =
        readOnlyViolation("rollbackDestinationTableCreation")

    override fun rollbackTemporaryTableCreation(session: ConnectorSession?, handle: JdbcOutputTableHandle?): Unit =
        readOnlyViolation("rollbackTemporaryTableCreation")

    override fun setTableProperties(
        session: ConnectorSession?,
        handle: JdbcTableHandle?,
        properties: Map<String, Optional<Any>>?,
    ): Unit = readOnlyViolation("setTableProperties")

    // --- column DDL ---

    override fun addColumn(session: ConnectorSession?, handle: JdbcTableHandle?, column: ColumnMetadata?, position: ColumnPosition?): Unit =
        readOnlyViolation("addColumn")

    override fun dropColumn(session: ConnectorSession?, handle: JdbcTableHandle?, column: JdbcColumnHandle?): Unit =
        readOnlyViolation("dropColumn")

    override fun renameColumn(
        session: ConnectorSession?,
        handle: JdbcTableHandle?,
        jdbcColumn: JdbcColumnHandle?,
        newColumnName: String?,
    ): Unit = readOnlyViolation("renameColumn")

    override fun setColumnType(session: ConnectorSession?, handle: JdbcTableHandle?, column: JdbcColumnHandle?, type: Type?): Unit =
        readOnlyViolation("setColumnType")

    override fun dropNotNullConstraint(session: ConnectorSession?, handle: JdbcTableHandle?, column: JdbcColumnHandle?): Unit =
        readOnlyViolation("dropNotNullConstraint")

    // --- comments ---

    override fun setTableComment(session: ConnectorSession?, handle: JdbcTableHandle?, comment: Optional<String>?): Unit =
        readOnlyViolation("setTableComment")

    override fun setColumnComment(
        session: ConnectorSession?,
        handle: JdbcTableHandle?,
        column: JdbcColumnHandle?,
        comment: Optional<String>?,
    ): Unit = readOnlyViolation("setColumnComment")

    // --- DML ---

    override fun beginInsertTable(
        session: ConnectorSession?,
        tableHandle: JdbcTableHandle?,
        columns: List<JdbcColumnHandle>?,
    ): JdbcOutputTableHandle = readOnlyViolation("beginInsertTable")

    override fun finishInsertTable(session: ConnectorSession?, handle: JdbcOutputTableHandle?, pageSinkIds: Set<Long>?): Unit =
        readOnlyViolation("finishInsertTable")

    override fun buildInsertSql(handle: JdbcOutputTableHandle?, columnWriters: List<WriteFunction>?): String =
        readOnlyViolation("buildInsertSql")

    override fun beginMerge(
        session: ConnectorSession?,
        handle: JdbcTableHandle?,
        updateColumnHandles: Map<Int, Collection<ColumnHandle>>?,
        rollbackActionCollector: Consumer<Runnable>?,
        retryMode: RetryMode?,
    ): JdbcMergeTableHandle = readOnlyViolation("beginMerge")

    override fun finishMerge(session: ConnectorSession?, handle: JdbcMergeTableHandle?, pageSinkIds: Set<Long>?): Unit =
        readOnlyViolation("finishMerge")

    override fun delete(session: ConnectorSession?, handle: JdbcTableHandle?): OptionalLong =
        readOnlyViolation("delete")

    override fun update(session: ConnectorSession?, handle: JdbcTableHandle?): OptionalLong =
        readOnlyViolation("update")

    // --- raw statement escape hatches (system.execute choke point, PLAN G7.5) ---

    override fun execute(session: ConnectorSession?, query: String?): Unit =
        readOnlyViolation("execute")

    override fun getConnection(session: ConnectorSession?): Connection =
        readOnlyViolation("getConnection(session) — only write paths (system.execute, merge sink) use this overload")

    override fun getConnection(session: ConnectorSession?, handle: JdbcOutputTableHandle?): Connection =
        readOnlyViolation("getConnection(session, outputTableHandle)")

    companion object {
        const val READ_ONLY_MESSAGE = "The Doris connector is read-only and does not allow any mutating operation"
    }
}
