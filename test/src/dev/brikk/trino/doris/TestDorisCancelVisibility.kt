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

import com.google.common.util.concurrent.MoreExecutors.directExecutor
import io.trino.testing.TestingConnectorSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class TestDorisCancelVisibility {
    @Test
    fun testWarningIsOncePerConnectorAndCancellationStillRuns() {
        val warnings = mutableListOf<LogRecord>()
        val logger = Logger.getLogger(DorisClusterScopedCancel::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { if (record.level == Level.WARNING) warnings.add(record) }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val oldLevel = logger.level
        logger.level = Level.ALL
        logger.addHandler(handler)
        val marker = "trino_statement_id=visibility_test"
        val killed = mutableListOf<String>()
        var aborts = 0
        val session = TestingConnectorSession.builder().build()
        val failedSet = SQLException("access denied for session variable", "42000")
        val factory = DorisCancelConnectionFactory { _, _ -> helper(marker, killed, failedSet) }
        val cancel = DorisClusterScopedCancel(factory, enabled = true, executor = directExecutor())
        try {
            repeat(2) {
                val result = proxy(ResultSet::class.java) { _, _ -> null }
                val raw = proxy(PreparedStatement::class.java) { method, _ ->
                    when (method) {
                        "executeQuery" -> result
                        "close" -> null
                        else -> error("Unexpected scan call: $method")
                    }
                }
                val connection = proxy(Connection::class.java) { method, _ ->
                    check(method == "abort") { "Unexpected scan connection call: $method" }
                    aborts++
                    null
                }
                DorisPreparedStatement(raw, connection) { done -> cancel.cancel(session, marker, done) }.use {
                    it.executeQuery()
                    it.cancel()
                }
            }
            assertThat(aborts).isEqualTo(2)
            assertThat(killed).hasSize(2)
            assertThat(warnings).hasSize(1)
            assertThat(warnings.single().message).contains("fetch_all_fe_for_system_table", "best-effort", "once per connector")
            assertThat(warnings.single().thrown).isSameAs(failedSet)
            // A separate catalog must not have its warning suppressed by the first one.
            DorisClusterScopedCancel(factory, enabled = true, executor = directExecutor()).cancel(session, marker) { true }
            assertThat(warnings).hasSize(2)
            DorisClusterScopedCancel({ _, _ -> helper(marker, killed, null) }, enabled = true, executor = directExecutor())
                .cancel(session, marker) { true }
            assertThat(warnings).hasSize(2)
            assertThat(killed).hasSize(4)
        } finally {
            logger.removeHandler(handler)
            logger.level = oldLevel
        }
    }

    @Test
    fun testProcesslistVisibilityCanBeEnabledOnDoris() {
        DorisTestCluster.openRootConnection().use { connection ->
            connection.createStatement().use { statement ->
                for (enabled in listOf(false, true)) {
                    statement.execute("SET fetch_all_fe_for_system_table = $enabled")
                    statement.executeQuery("SELECT @@fetch_all_fe_for_system_table").use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getBoolean(1)).isEqualTo(enabled)
                    }
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // Strict JDBC script for both SET failure and successful kill.
    private fun helper(marker: String, killed: MutableList<String>, failedSet: SQLException?): Connection {
        var running = true
        val statement = proxy(Statement::class.java) { method, args ->
            when (method) {
                "execute" -> {
                    val sql = args[0] as String
                    if (sql.startsWith("SET ")) {
                        if (failedSet != null) throw failedSet
                    } else {
                        assertThat(sql).isEqualTo("KILL QUERY \"a-b\"")
                        killed.add(sql)
                        running = false
                    }
                    false
                }
                "executeQuery" -> {
                    assertThat(args[0]).isEqualTo("SHOW FULL PROCESSLIST")
                    var row = running
                    proxy(ResultSet::class.java) { operation, columns ->
                        when (operation) {
                            "next" -> row.also { row = false }
                            "getString" -> mapOf("Info" to "SELECT 1 /*$marker*/", "Command" to "Query", "QueryId" to "a-b")[columns[0]]
                            "close" -> null
                            else -> error("Unexpected helper result call: $operation")
                        }
                    }
                }
                "close" -> null
                else -> error("Unexpected helper statement call: $method")
            }
        }
        return proxy(Connection::class.java) { method, _ ->
            when (method) {
                "createStatement" -> statement
                "setNetworkTimeout", "close" -> null
                else -> error("Unexpected helper connection call: $method")
            }
        }
    }

    private fun <T> proxy(type: Class<T>, handler: (String, Array<out Any?>) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> handler(method.name, args ?: emptyArray()) })
}
