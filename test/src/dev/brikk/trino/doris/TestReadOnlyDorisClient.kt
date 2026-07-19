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

import io.trino.plugin.jdbc.JdbcClient
import io.trino.spi.TrinoException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Reflection audit of the read-only guard (PLAN G7.2): EVERY method on the Trino 483
 * [JdbcClient] surface must be classified below. A future base-jdbc upgrade that adds a new
 * (potentially mutating) method fails [testEveryJdbcClientMethodIsClassified] loudly instead
 * of silently forwarding through the guard.
 *
 * Every MUTATOR must (a) be overridden by [ReadOnlyDorisClient] itself and (b) throw the
 * read-only error WITHOUT touching the delegate (the delegate here booby-traps every call).
 */
class TestReadOnlyDorisClient {
    private val untouchableDelegate = Proxy.newProxyInstance(
        JdbcClient::class.java.classLoader,
        arrayOf(JdbcClient::class.java),
    ) { _, method, _ ->
        throw AssertionError("read-only guard leaked a call to the delegate: $method")
    } as JdbcClient

    private val client = ReadOnlyDorisClient(untouchableDelegate)

    @Test
    fun testEveryJdbcClientMethodIsClassified() {
        val unclassified = jdbcClientMethods()
            .map { signature(it) }
            .filter { it !in MUTATORS && it !in READ_AND_PROBE_METHODS && it !in CAPABILITY_DENIALS }
        assertThat(unclassified)
            .describedAs(
                "Unclassified JdbcClient methods after a base-jdbc change — decide for each whether it mutates " +
                    "(add to MUTATORS + override in ReadOnlyDorisClient) or is read-safe (add to READ_AND_PROBE_METHODS)",
            )
            .isEmpty()
        // ... and the classification lists must not drift out of sync with the interface.
        val known = jdbcClientMethods().map { signature(it) }.toSet()
        assertThat(MUTATORS + READ_AND_PROBE_METHODS + CAPABILITY_DENIALS).allSatisfy { assertThat(known).contains(it) }
    }

    @Test
    fun testEveryMutatorIsOverriddenAndThrowsReadOnly() {
        val mutatorMethods = jdbcClientMethods().filter { signature(it) in MUTATORS }
        assertThat(mutatorMethods).hasSize(MUTATORS.size)
        for (method in mutatorMethods) {
            val implementation = ReadOnlyDorisClient::class.java.getMethod(method.name, *method.parameterTypes)
            assertThat(implementation.declaringClass)
                .describedAs("mutator must be overridden by the guard itself: ${signature(method)}")
                .isEqualTo(ReadOnlyDorisClient::class.java)

            val arguments = method.parameterTypes.map { defaultArgument(it) }.toTypedArray()
            assertThatThrownBy { method.invoke(client, *arguments) }
                .describedAs(signature(method))
                .isInstanceOf(InvocationTargetException::class.java)
                .cause()
                .isInstanceOf(TrinoException::class.java)
                .hasMessageContaining(ReadOnlyDorisClient.READ_ONLY_MESSAGE)
        }
    }

    @Test
    fun testMergeCapabilityIsDenied() {
        assertThat(client.supportsMerge()).isFalse()
        val implementation = ReadOnlyDorisClient::class.java.getMethod("supportsMerge")
        assertThat(implementation.declaringClass).isEqualTo(ReadOnlyDorisClient::class.java)
    }

    private fun jdbcClientMethods(): List<Method> =
        JdbcClient::class.java.methods.filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }

    private fun signature(method: Method): String =
        "${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})"

    private fun defaultArgument(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
    }

    companion object {
        /** Every mutating method on the 483 JdbcClient surface; the guard throws from each. */
        private val MUTATORS = setOf(
            "toWriteMapping(ConnectorSession,Type)",
            "getMaxWriteParallelism(ConnectorSession)",
            "createSchema(ConnectorSession,String)",
            "dropSchema(ConnectorSession,String,boolean)",
            "renameSchema(ConnectorSession,String,String)",
            "createTable(ConnectorSession,ConnectorTableMetadata)",
            "beginCreateTable(ConnectorSession,ConnectorTableMetadata,Consumer)",
            "commitCreateTable(ConnectorSession,JdbcOutputTableHandle,Set)",
            "renameTable(ConnectorSession,JdbcTableHandle,SchemaTableName)",
            "dropTable(ConnectorSession,JdbcTableHandle)",
            "truncateTable(ConnectorSession,JdbcTableHandle)",
            "rollbackDestinationTableCreation(ConnectorSession,RemoteTableName)",
            "rollbackTemporaryTableCreation(ConnectorSession,JdbcOutputTableHandle)",
            "setTableProperties(ConnectorSession,JdbcTableHandle,Map)",
            "setTableComment(ConnectorSession,JdbcTableHandle,Optional)",
            "setColumnComment(ConnectorSession,JdbcTableHandle,JdbcColumnHandle,Optional)",
            "addColumn(ConnectorSession,JdbcTableHandle,ColumnMetadata,ColumnPosition)",
            "dropColumn(ConnectorSession,JdbcTableHandle,JdbcColumnHandle)",
            "renameColumn(ConnectorSession,JdbcTableHandle,JdbcColumnHandle,String)",
            "setColumnType(ConnectorSession,JdbcTableHandle,JdbcColumnHandle,Type)",
            "dropNotNullConstraint(ConnectorSession,JdbcTableHandle,JdbcColumnHandle)",
            "beginInsertTable(ConnectorSession,JdbcTableHandle,List)",
            "finishInsertTable(ConnectorSession,JdbcOutputTableHandle,Set)",
            "buildInsertSql(JdbcOutputTableHandle,List)",
            "beginMerge(ConnectorSession,JdbcTableHandle,Map,Consumer,RetryMode)",
            "finishMerge(ConnectorSession,JdbcMergeTableHandle,Set)",
            "delete(ConnectorSession,JdbcTableHandle)",
            "update(ConnectorSession,JdbcTableHandle)",
            // system.execute choke point (PLAN G7.5): the auto-installed ExecuteProcedure runs
            // raw SQL over getConnection(session) — both raw-statement doors are shut here.
            "execute(ConnectorSession,String)",
            "getConnection(ConnectorSession)",
            "getConnection(ConnectorSession,JdbcOutputTableHandle)",
        )

        /** Write-capability probe that must answer "no" rather than throw. */
        private val CAPABILITY_DENIALS = setOf(
            "supportsMerge()",
        )

        /** Read/metadata/pushdown-probe surface — forwarding to DorisClient is safe. */
        private val READ_AND_PROBE_METHODS = setOf(
            "schemaExists(ConnectorSession,String)",
            "getSchemaNames(ConnectorSession)",
            "getTableNames(ConnectorSession,Optional)",
            "getTableHandle(ConnectorSession,SchemaTableName)",
            "getTableHandle(ConnectorSession,PreparedQuery)",
            "getProcedureHandle(ConnectorSession,ProcedureQuery)",
            "getColumns(ConnectorSession,SchemaTableName,RemoteTableName)",
            "getAllTableColumns(ConnectorSession,Optional)",
            "getAllTableComments(ConnectorSession,Optional)",
            "toColumnMapping(ConnectorSession,Connection,JdbcTypeHandle)",
            "toColumnMappings(ConnectorSession,List)",
            "getSupportedType(ConnectorSession,Type)",
            "supportsAggregationPushdown(ConnectorSession,JdbcTableHandle,List,Map,List)",
            "implementAggregation(ConnectorSession,AggregateFunction,Map)",
            "convertPredicate(ConnectorSession,ConnectorExpression,Map)",
            "convertProjection(ConnectorSession,JdbcTableHandle,ConnectorExpression,Map)",
            "getSplits(ConnectorSession,JdbcTableHandle)",
            "getSplits(ConnectorSession,JdbcProcedureHandle)",
            "getConnection(ConnectorSession,JdbcSplit,JdbcTableHandle)",
            "getConnection(ConnectorSession,JdbcSplit,JdbcProcedureHandle)",
            "abortReadConnection(Connection,ResultSet)",
            "prepareQuery(ConnectorSession,JdbcTableHandle,Optional,List,Map)",
            "buildSql(ConnectorSession,Connection,JdbcSplit,JdbcTableHandle,List)",
            "buildProcedure(ConnectorSession,Connection,JdbcSplit,JdbcProcedureHandle)",
            "implementJoin(ConnectorSession,JoinType,PreparedQuery,Map,PreparedQuery,Map,List,JoinStatistics)",
            "legacyImplementJoin(ConnectorSession,JoinType,PreparedQuery,PreparedQuery,List,Map,Map,JoinStatistics)",
            "supportsTopN(ConnectorSession,JdbcTableHandle,List)",
            "isTopNGuaranteed(ConnectorSession)",
            "supportsLimit()",
            "isLimitGuaranteed(ConnectorSession)",
            "getTableComment(ResultSet)",
            "supportsRetries()",
            "getPreparedStatement(Connection,String,Optional)",
            "getTableStatistics(ConnectorSession,JdbcTableHandle)",
            "getSystemTable(ConnectorSession,SchemaTableName)",
            "quoted(String)",
            "quoted(RemoteTableName)",
            "getTableProperties(ConnectorSession,JdbcTableHandle)",
            "getTableScanRedirection(ConnectorSession,JdbcTableHandle)",
            "getMaxColumnNameLength(ConnectorSession)",
            "getPrimaryKeys(ConnectorSession,RemoteTableName)",
        )
    }
}
