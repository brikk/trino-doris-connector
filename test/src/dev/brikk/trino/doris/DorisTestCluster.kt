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
import java.util.concurrent.TimeUnit

/**
 * Direct (non-Trino) access to the live compose cluster for fixtures, side-effect oracles,
 * `information_schema.processlist` polling, and the FE audit log — the proven observability
 * substrate for pushed remote SQL (ledger §E: `fe.audit.log` `Stmt=` is verbatim).
 */
object DorisTestCluster {
    const val GOLDEN_NATIVE_VERSION = "doris-4.1.5-release-917b20be838"
    private const val FE_CONTAINER = "trino-doris-fe"
    private const val AUDIT_LOG_PATH = "/opt/apache-doris/fe/log/fe.audit.log"

    fun openRootConnection(): Connection = openConnection("root")

    /** Fail before using fixtures if a different FE/BE release is on the validation port. */
    fun requireGoldenVersion() {
        openRootConnection().use { connection ->
            connection.createStatement().use { statement ->
                for (sql in listOf("SHOW FRONTENDS", "SHOW BACKENDS")) {
                    statement.executeQuery(sql).use { result ->
                        requireGoldenNodes(result, sql)
                    }
                }
            }
        }
    }

    private fun requireGoldenNodes(result: java.sql.ResultSet, sql: String) {
        var alive = 0
        while (result.next()) {
            val version = result.getString("Version")
            check(version == GOLDEN_NATIVE_VERSION) { "$sql returned $version; expected $GOLDEN_NATIVE_VERSION" }
            if (result.getBoolean("Alive")) alive++
        }
        check(alive > 0) { "$sql has no alive $GOLDEN_NATIVE_VERSION nodes" }
    }

    fun openConnection(user: String): Connection =
        DorisTestSafety.connectSingle("${DorisQueryRunner.JDBC_URL}/?user=$user")

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

    /** The Doris QueryId of the processlist row whose statement contains [marker], if live. */
    fun runningDorisQueryId(marker: String): String? =
        queryRows("SELECT QueryId, Info FROM information_schema.processlist")
            .firstOrNull { (_, info) -> info?.contains(marker) == true }
            ?.first

    private fun queryRows(sql: String): List<Pair<String?, String?>> {
        openRootConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet ->
                    val rows = ArrayList<Pair<String?, String?>>()
                    while (resultSet.next()) {
                        rows.add(resultSet.getString(1) to resultSet.getString(2))
                    }
                    return rows
                }
            }
        }
    }

    // A send-blocked client can retain its processlist row after server-side cancellation.
    fun feLogShowsCancellation(dorisQueryId: String): Boolean =
        logMatches("/opt/apache-doris/fe/log/fe.log", dorisQueryId).any {
            it.contains("killed by timeout checker", ignoreCase = true) || it.contains("cancel", ignoreCase = true)
        }

    fun feLogShowsQueryTimeout(dorisQueryId: String): Boolean =
        logMatches("/opt/apache-doris/fe/log/fe.log", dorisQueryId).any { it.contains("killed by timeout checker") }

    /**
     * Audit-log lines containing [marker] (e.g. the `trino_query_id=<id>` comment rendered by
     * [DorisRemoteQueryModifier]). The FE audit logger flushes asynchronously (~5s lag
     * observed), so callers poll via [awaitAuditLogStatements].
     */
    fun auditLogStatements(marker: String): List<String> = logMatches(AUDIT_LOG_PATH, marker)

    private fun logMatches(path: String, marker: String): List<String> {
        DorisTestSafety.requireSingle()
        return runTestProcess(listOf("docker", "exec", FE_CONTAINER, "grep", "-a", "-F", "--", marker, path)).grepMatches()
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

    /**
     * Parameterized inserts as root — required for fixtures whose values contain NUL,
     * zero-width, or control characters that cannot travel through SQL literal quoting.
     */
    fun executePreparedAsRoot(sql: String, rows: List<List<Any?>>) {
        openRootConnection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                for (row in rows) {
                    row.forEachIndexed { index, value ->
                        when (value) {
                            null -> statement.setNull(index + 1, java.sql.Types.VARCHAR)
                            is Int -> statement.setInt(index + 1, value)
                            is String -> statement.setString(index + 1, value)
                            else -> error("unsupported fixture parameter type: ${value.javaClass}")
                        }
                    }
                    statement.executeUpdate()
                }
            }
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

    private const val POLL_INTERVAL_MILLIS = 250L
}
