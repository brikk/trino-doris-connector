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

import io.trino.plugin.jdbc.ConnectionFactory
import io.trino.spi.connector.ConnectorSession
import io.trino.testing.TestingConnectorSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cluster-scoped cancel: pure-function pins, bookkeeping/failure-tolerance units, and the
 * live single-FE probe pins (trace-id behavior on 4.1.3, KILL-by-QueryId, unknown-query-id
 * classification). The cross-FE forwarding proof lives in `TestDorisMultiFeFailover`
 * (overlay-guarded); the end-to-end connector path in `TestDorisCancellation`.
 */
class TestDorisClusterScopedCancel {
    // --- pure functions ---

    @Test
    fun testQueryIdValidation() {
        assertThat(DorisClusterScopedCancel.isValidQueryId("ce96d055444242d1-a5a580d0c15a4c40")).isTrue()
        assertThat(DorisClusterScopedCancel.isValidQueryId("2a7996e4e05a462d-b5b5071a01104ecb")).isTrue()
        assertThat(DorisClusterScopedCancel.isValidQueryId(null)).isFalse()
        assertThat(DorisClusterScopedCancel.isValidQueryId("")).isFalse()
        assertThat(DorisClusterScopedCancel.isValidQueryId("N/A")).isFalse()
        assertThat(DorisClusterScopedCancel.isValidQueryId("nodash")).isFalse()
        assertThat(DorisClusterScopedCancel.isValidQueryId("""x"; KILL QUERY "y""")).isFalse()
    }

    @Test
    fun testRunningCancelCandidateClassification() {
        val marker = "trino_statement_id=00000000-0000-0000-0000-000000000001"
        // running row carrying the marker: candidate
        assertThat(DorisClusterScopedCancel.isRunningCancelCandidate("Query", "SELECT ... /*$marker*/", marker)).isTrue()
        // idle pooled connections never match
        assertThat(DorisClusterScopedCancel.isRunningCancelCandidate("Sleep", "SELECT ... /*$marker*/", marker)).isFalse()
        // the helper's own statements never match
        assertThat(DorisClusterScopedCancel.isRunningCancelCandidate("Query", "  show full processlist /*$marker*/", marker)).isFalse()
        assertThat(
            DorisClusterScopedCancel.isRunningCancelCandidate("Query", """  kill query "x" /*$marker*/""", marker),
        ).isFalse()
        // other queries' markers never match
        assertThat(
            DorisClusterScopedCancel.isRunningCancelCandidate("Query", "SELECT 1 /*${marker}0*/", marker),
        ).isFalse()
        assertThat(DorisClusterScopedCancel.isRunningCancelCandidate("Query", "SELECT '$marker'", marker)).isFalse()
        assertThat(DorisClusterScopedCancel.isRunningCancelCandidate("Query", "SELECT 1 /*prefix_$marker*/", marker)).isFalse()
        assertThat(
            DorisClusterScopedCancel.isRunningCancelCandidate("Query", "SELECT 'KILL QUERY', 'SHOW FULL PROCESSLIST' /*$marker*/", marker),
        ).isTrue()
        assertThat(DorisClusterScopedCancel.isRunningCancelCandidate("Query", null, marker)).isFalse()
    }

    @Test
    fun testUnknownQueryIdClassification() {
        assertThat(
            DorisClusterScopedCancel.isUnknownQueryId(
                SQLException("errCode = 2, detailMessage = Unknown query id: deadbeef-badc0ffee"),
            ),
        ).isTrue()
        // nested cause
        assertThat(
            DorisClusterScopedCancel.isUnknownQueryId(RuntimeException(SQLException("Unknown query id: x"))),
        ).isTrue()
        assertThat(DorisClusterScopedCancel.isUnknownQueryId(SQLException("Access denied"))).isFalse()
        assertThat(DorisClusterScopedCancel.isUnknownQueryId(null)).isFalse()
    }

    // --- bookkeeping + failure tolerance ---

    @Test
    fun testHelperFailureNeverPropagatesAndEachCallDispatches() {
        val attempted = CountDownLatch(2)
        val failingFactory = ConnectionFactory {
            attempted.countDown()
            throw SQLException("no cluster reachable")
        }
        val cancel = DorisClusterScopedCancel(failingFactory, enabled = true)
        val marker = "trino_statement_id=${UUID.randomUUID()}"
        val before = DorisClusterScopedCancel.killsDispatched.get()
        // No registry or in-flight deduplication: the wrapper owns dispatch-once behavior.
        repeat(2) { cancel.cancel(SESSION, marker) { true } }
        assertThat(DorisClusterScopedCancel.killsDispatched.get()).isEqualTo(before + 2)
        assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun testDisabledDoesNothing() {
        val cancel = DorisClusterScopedCancel({ throw AssertionError("must not open") }, enabled = false)
        val before = DorisClusterScopedCancel.killsDispatched.get()
        cancel.cancel(SESSION, "trino_statement_id=${UUID.randomUUID()}") { throw AssertionError("must not read execution state") }
        assertThat(DorisClusterScopedCancel.killsDispatched.get()).isEqualTo(before)
    }

    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ThrowsCount") // Strict fake-JDBC script with its assertions.
    fun testProductionHelperRetriesEmptyAndUnassignedRowsUntilReleased() {
        val marker = "trino_statement_id=${UUID.randomUUID()}"
        val queryId = "ce96d055444242d1-a5a580d0c15a4c40"
        val scans = listOf(
            emptyList(), emptyList(), emptyList(), listOf(null), listOf(""), listOf("N/A"),
            listOf("""x"; KILL QUERY "y"""), listOf(queryId), listOf(queryId), emptyList(),
        )
        val scanCount = AtomicInteger()
        val finished = AtomicBoolean()
        val closed = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val sql = CopyOnWriteArrayList<String>()
        val statement = jdbcProxy(Statement::class.java) { method, args ->
            when (method) {
                "execute" -> { sql.add(args[0] as String); false }
                "executeQuery" -> {
                    sql.add(args[0] as String)
                    val scan = scanCount.getAndIncrement()
                    events.add("scan $scan")
                    // Completion DURING an empty scan must not terminate that iteration.
                    // Finished execution can still leave visible, as-yet-unkillable server rows.
                    if (scan == 1) finished.set(true)
                    val rows = scans[scan]
                    var row = -1
                    jdbcProxy(ResultSet::class.java) { operation, columns ->
                        when (operation) {
                            "next" -> ++row < rows.size
                            "getString" -> when (columns[0]) {
                                "Info" -> "SELECT sleep(30) /*$marker*/"
                                "Command" -> "Query"
                                "QueryId" -> rows[row]
                                else -> throw AssertionError("unexpected column: ${columns[0]}")
                            }
                            "close" -> null
                            else -> throw AssertionError("unexpected result call: $operation")
                        }
                    }
                }
                "close" -> null
                else -> throw AssertionError("unexpected statement call: $method")
            }
        }
        val connection = jdbcProxy(Connection::class.java) { method, _ ->
            when (method) {
                "createStatement" -> statement
                "close" -> { closed.countDown(); null }
                else -> throw AssertionError("unexpected connection call: $method")
            }
        }

        DorisClusterScopedCancel({ connection }, enabled = true).cancel(SESSION, marker) {
            finished.get().also { events.add("finished $it") }
        }

        assertThat(closed.await(10, TimeUnit.SECONDS)).describedAs("production helper released its connection").isTrue()
        assertThat(scanCount.get()).isEqualTo(scans.size)
        assertThat(events).containsExactlyElementsOf(scans.indices.flatMap { listOf("finished ${it >= 2}", "scan $it") })
        assertThat(sql).containsExactly(
            "SET fetch_all_fe_for_system_table = true",
            "SHOW FULL PROCESSLIST", "SHOW FULL PROCESSLIST", "SHOW FULL PROCESSLIST",
            "SHOW FULL PROCESSLIST", "SHOW FULL PROCESSLIST",
            "SHOW FULL PROCESSLIST", "SHOW FULL PROCESSLIST",
            "SHOW FULL PROCESSLIST", """KILL QUERY "$queryId"""",
            "SHOW FULL PROCESSLIST", """KILL QUERY "$queryId"""",
            "SHOW FULL PROCESSLIST",
        )
    }

    // --- live probe pins (single-FE cluster) ---

    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth") // One live probe, including failure-path cleanup.
    fun testProductionHelperCancelsOnlyItsStatementAndRepeatIsHarmless() {
        val queryMarker = "trino_query_id=${SESSION.queryId}"
        val markers = List(2) { "trino_statement_id=${UUID.randomUUID()}" }
        val helpersClosed = CountDownLatch(2)
        val cancel = DorisClusterScopedCancel(
            ConnectionFactory {
                val connection = openRoot()
                object : Connection by connection {
                    override fun close() {
                        try {
                            connection.close()
                        } finally {
                            helpersClosed.countDown()
                        }
                    }
                }
            },
            enabled = true,
        )
        val pool = Executors.newFixedThreadPool(2)
        val victims = markers.map { marker ->
            pool.submit<String> {
                runCatching {
                    openRoot().use { connection ->
                        connection.createStatement().use { it.execute("SELECT sleep(30) /*$queryMarker*/ /*$marker*/") }
                    }
                }.exceptionOrNull()?.message ?: "completed"
            }
        }
        val dispatched = BooleanArray(2)
        fun cancelOnce(index: Int) {
            if (!dispatched[index]) {
                dispatched[index] = true
                cancel.cancel(SESSION, markers[index]) { victims[index].isDone }
            }
        }
        try {
            openRoot().use { helper ->
                helper.createStatement().use { statement ->
                    var queryIds = emptyList<String?>()
                    awaitTrue("both statement markers have assigned QueryIds: $markers") {
                        queryIds = markers.map { marker ->
                            runningQueryIds(statement, marker).singleOrNull()?.takeIf(DorisClusterScopedCancel::isValidQueryId)
                        }
                        queryIds.all { it != null }
                    }
                    assertThat(queryIds).doesNotHaveDuplicates()
                    cancelOnce(0)
                    assertThat(victims[0].get(20, TimeUnit.SECONDS)).contains("cancel query by user")
                    awaitTrue("helper A finished verifying release") { helpersClosed.count == 1L }
                    assertThat(runningQueryIds(statement, markers[0])).isEmpty()
                    assertThat(runningQueryIds(statement, markers[1])).contains(queryIds[1])
                    assertThat(victims[1].isDone).describedAs("sibling with the same Trino query id remains live").isFalse()

                    // Depending on cleanup timing, repeat kills succeed or report Unknown query id.
                    val repeated = runCatching { statement.execute("""KILL QUERY "${queryIds[0]}"""") }.exceptionOrNull()
                    assertThat(repeated == null || DorisClusterScopedCancel.isUnknownQueryId(repeated))
                        .describedAs("repeat-kill outcome: $repeated").isTrue()
                    val neverExisted = runCatching {
                        statement.execute("""KILL QUERY "deadbeef00000000-badc0ffee0000000"""")
                    }.exceptionOrNull()
                    assertThat(DorisClusterScopedCancel.isUnknownQueryId(neverExisted)).describedAs("unknown-id error: $neverExisted").isTrue()
                    cancelOnce(1)
                    assertThat(victims[1].get(20, TimeUnit.SECONDS)).contains("cancel query by user")
                    assertThat(helpersClosed.await(20, TimeUnit.SECONDS)).isTrue()
                    assertThat(runningQueryIds(statement, markers[1])).isEmpty()
                }
            }
        } finally {
            markers.indices.forEach(::cancelOnce)
            pool.shutdownNow()
            assertThat(pool.awaitTermination(35, TimeUnit.SECONDS)).describedAs("live probe executions exited").isTrue()
            assertThat(helpersClosed.await(20, TimeUnit.SECONDS)).describedAs("live probe helpers closed").isTrue()
        }
    }

    private fun runningQueryIds(statement: Statement, marker: String): List<String?> =
        statement.executeQuery("SHOW FULL PROCESSLIST").use { rs ->
            buildList {
                while (rs.next()) {
                    val info = rs.getString("Info") ?: continue
                    if (DorisClusterScopedCancel.isRunningCancelCandidate(rs.getString("Command"), info, marker)) {
                        val queryId = rs.getString("QueryId")
                        if (!DorisClusterScopedCancel.isValidQueryId(queryId)) {
                            println(
                                "Unassigned/invalid QueryId=${queryId?.let { "'$it' (length=${it.length})" } ?: "<SQL NULL>"}; " +
                                    "Id=${rs.getString("Id")}; Command=${rs.getString("Command")}; State=${rs.getString("State")}; Info=$info",
                            )
                        }
                        add(queryId)
                    }
                }
            }
        }

    @Test
    fun testTraceIdKillBehaviorPin() {
        // The reference implementation's production scar: KILL QUERY "<trace-id>" is a SILENT
        // NO-OP on Doris 4.1.2. On 4.1.3 our probe found it WORKING (single- and cross-FE) —
        // i.e. the behavior is VERSION-UNSTABLE, which is exactly why the connector kills by
        // the processlist QueryId instead (works on both). This pin documents the 4.1.3 state;
        // if it starts FAILING here, the no-op regressed and this comment gains a version note.
        val pool = Executors.newSingleThreadExecutor()
        val victim = pool.submit<String> {
            runCatching {
                openRoot().use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("SET session_context = 'trace_id:cscpin_trace'")
                        statement.execute("SELECT sleep(30) /*csc_trace_pin*/")
                    }
                }
            }.exceptionOrNull()?.message ?: "completed"
        }
        try {
            openRoot().use { helper ->
                helper.createStatement().use { statement ->
                    awaitTrue {
                        statement.executeQuery("SHOW FULL PROCESSLIST").use { rs ->
                            generateSequence { if (rs.next()) rs.getString("Info") else null }
                                .any { it.contains("csc_trace_pin") }
                        }
                    }
                    // returns OK on 4.1.3 (and actually kills; on 4.1.2 it silently did NOT)
                    statement.execute("""KILL QUERY "cscpin_trace"""")
                    val outcome = victim.get(30, TimeUnit.SECONDS)
                    assertThat(outcome).contains("cancel query by user")
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        private val SESSION: ConnectorSession = TestingConnectorSession.builder().build()

        private fun openRoot(): Connection = DriverManager.getConnection("${DorisQueryRunner.JDBC_URL}/?user=root")

        private fun <T> jdbcProxy(type: Class<T>, handler: (String, Array<out Any?>) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                handler(method.name, args ?: emptyArray())
            })

        private fun awaitTrue(description: String = "condition", condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "$description not met within 30s" }
                Thread.sleep(250)
            }
        }
    }
}
