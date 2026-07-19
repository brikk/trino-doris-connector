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

import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/**
 * Direct (non-Trino) access to the live compose cluster for fixtures, side-effect oracles,
 * `information_schema.processlist` polling, and the FE audit log — the proven observability
 * substrate for pushed remote SQL (ledger §E: `fe.audit.log` `Stmt=` is verbatim).
 */
object DorisTestCluster {
    private const val FE_CONTAINER = "trino-doris-fe"
    private const val AUDIT_LOG_PATH = "/opt/apache-doris/fe/log/fe.audit.log"

    fun openRootConnection(): Connection = openConnection("root")

    fun openConnection(user: String): Connection =
        DriverManager.getConnection("${DorisQueryRunner.JDBC_URL}/?user=$user")

    /** Scalar query oracle as root (side-effect checks, processlist polling). */
    fun queryScalar(sql: String): String? {
        openRootConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet ->
                    return if (resultSet.next()) resultSet.getString(1) else null
                }
            }
        }
    }

    fun querySingleColumn(sql: String): List<String?> {
        openRootConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet ->
                    val values = ArrayList<String?>()
                    while (resultSet.next()) {
                        values.add(resultSet.getString(1))
                    }
                    return values
                }
            }
        }
    }

    /** Statements currently running on the Doris FE (excluding the poll query itself). */
    fun runningStatements(): List<String> =
        querySingleColumn("SELECT INFO FROM information_schema.processlist")
            .filterNotNull()
            .filterNot { it.contains("information_schema.processlist") }

    /**
     * Audit-log lines containing [marker] (e.g. the `trino_query_id=<id>` comment rendered by
     * [DorisRemoteQueryModifier]). The FE audit logger flushes asynchronously (~5s lag
     * observed), so callers poll via [awaitAuditLogStatements].
     */
    fun auditLogStatements(marker: String): List<String> {
        val process = ProcessBuilder(
            "docker", "exec", FE_CONTAINER,
            "sh", "-c", "grep -F -- ${shellQuote(marker)} $AUDIT_LOG_PATH || true",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "docker exec grep timed out" }
        return output.lines().filter { it.isNotBlank() }
    }

    fun awaitAuditLogStatements(marker: String, timeoutMillis: Long): List<String> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            val lines = auditLogStatements(marker)
            if (lines.isNotEmpty()) {
                return lines
            }
            check(System.nanoTime() < deadline) {
                "No fe.audit.log statement containing '$marker' within ${timeoutMillis}ms"
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    fun executeAsRoot(vararg statements: String) {
        openRootConnection().use { connection ->
            connection.createStatement().use { statement ->
                for (sql in statements) {
                    statement.execute(sql)
                }
            }
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private const val POLL_INTERVAL_MILLIS = 250L
}
