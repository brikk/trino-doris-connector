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
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        assertThat(DorisClusterScopedCancel.isValidQueryId("nodash")).isFalse()
        assertThat(DorisClusterScopedCancel.isValidQueryId("""x"; KILL QUERY "y""")).isFalse()
    }

    @Test
    fun testRunningCancelCandidateClassification() {
        val marker = "trino_query_id=20260719_1"
        // running row carrying the marker: candidate
        assertThat(DorisClusterScopedCancel.isRunningCancelCandidate("Query", "SELECT ... /*$marker*/", marker)).isTrue()
        // idle pooled connections never match
        assertThat(DorisClusterScopedCancel.isRunningCancelCandidate("Sleep", "SELECT ... /*$marker*/", marker)).isFalse()
        // the helper's own statements never match
        assertThat(DorisClusterScopedCancel.isRunningCancelCandidate("Query", "SHOW FULL PROCESSLIST", marker)).isFalse()
        assertThat(
            DorisClusterScopedCancel.isRunningCancelCandidate("Query", """KILL QUERY "x" /*$marker*/""", marker),
        ).isFalse()
        // other queries' markers never match
        assertThat(
            DorisClusterScopedCancel.isRunningCancelCandidate("Query", "SELECT 1 /*trino_query_id=20260719_2*/", marker),
        ).isFalse()
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
    fun testHelperFailureNeverPropagatesAndClearsInFlight() {
        val failingFactory = ConnectionFactory { throw SQLException("no cluster reachable") }
        val cancel = DorisClusterScopedCancel(failingFactory, enabled = true)
        val connection = fakeConnection()
        cancel.register(connection, SESSION)
        // must not throw, despite the helper factory failing on the async path
        cancel.onAbort(connection)
        // the in-flight slot must clear (async, in the helper's finally) so a later cancel
        // of the same query can retry — re-register+re-abort until the dispatch is accepted
        val before = DorisClusterScopedCancel.killsDispatched.get()
        awaitTrue {
            cancel.register(connection, SESSION)
            cancel.onAbort(connection)
            DorisClusterScopedCancel.killsDispatched.get() > before
        }
    }

    @Test
    fun testAbortWithoutRegistrationIsANoOp() {
        val cancel = DorisClusterScopedCancel({ throw AssertionError("must not open") }, enabled = true)
        cancel.onAbort(fakeConnection()) // unknown connection: nothing dispatched, nothing thrown
    }

    @Test
    fun testDisabledDoesNothing() {
        val cancel = DorisClusterScopedCancel({ throw AssertionError("must not open") }, enabled = false)
        val connection = fakeConnection()
        val before = DorisClusterScopedCancel.killsDispatched.get()
        cancel.register(connection, SESSION)
        cancel.onAbort(connection)
        assertThat(DorisClusterScopedCancel.killsDispatched.get()).isEqualTo(before)
    }

    // --- live probe pins (single-FE cluster) ---

    @Test
    fun testKillByQueryIdReleasesAndRepeatIsHarmless() {
        // EARLY-KILL NO-OP RACE (live-reproduced, 1/25 tight-loop rounds on 4.1.3): a
        // KILL QUERY "<QueryId>" issued in the first instants of a query's life — the
        // processlist row appears BEFORE the coordinator registers as cancellable —
        // returns OK but silently does nothing and the query runs to completion. The
        // production code therefore VERIFIES AND RETRIES within a bounded window
        // (DorisClusterScopedCancel.killUntilReleased); this pin exercises the same
        // scan->kill->verify->retry contract rather than a single fire-and-forget kill.
        val marker = "trino_query_id=csc_pin_${System.nanoTime()}"
        val pool = Executors.newSingleThreadExecutor()
        val victim = pool.submit<String> {
            runCatching {
                openRoot().use { it.createStatement().execute("SELECT sleep(30) /*$marker*/") }
            }.exceptionOrNull()?.message ?: "completed"
        }
        try {
            openRoot().use { helper ->
                helper.createStatement().use { statement ->
                    var lastKilled: String? = null
                    awaitTrue {
                        val queryIds = runningQueryIds(statement, marker)
                        for (queryId in queryIds) {
                            assertThat(DorisClusterScopedCancel.isValidQueryId(queryId)).isTrue()
                            statement.execute("""KILL QUERY "$queryId"""")
                            lastKilled = queryId
                        }
                        // released == no more running rows AFTER at least one kill was issued
                        lastKilled != null && queryIds.isEmpty()
                    }
                    // the blocked read unblocked with the server error — the completion signal
                    assertThat(victim.get(10, TimeUnit.SECONDS)).contains("cancel query by user")
                    // 4.1.3 pins: repeating a kill for a COMPLETED query is silently accepted;
                    // a NEVER-EXISTED id errors "Unknown query id" (classified as success)
                    runCatching { statement.execute("""KILL QUERY "$lastKilled"""") }
                    val neverExisted = runCatching {
                        statement.execute("""KILL QUERY "deadbeef00000000-badc0ffee0000000"""")
                    }.exceptionOrNull()
                    assertThat(DorisClusterScopedCancel.isUnknownQueryId(neverExisted)).isTrue()
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun runningQueryIds(statement: java.sql.Statement, marker: String): List<String> =
        statement.executeQuery("SHOW FULL PROCESSLIST").use { rs ->
            buildList {
                while (rs.next()) {
                    val info = rs.getString("Info") ?: continue
                    if (DorisClusterScopedCancel.isRunningCancelCandidate(rs.getString("Command"), info, marker)) {
                        add(rs.getString("QueryId"))
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

        /** A JDBC Connection stand-in for bookkeeping tests (no method is ever really called). */
        private fun fakeConnection(): Connection =
            Proxy.newProxyInstance(
                TestDorisClusterScopedCancel::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> false
                    "toString" -> "fake-connection"
                    else -> throw AssertionError("unexpected call: ${method.name}")
                }
            } as Connection

        private fun awaitTrue(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "condition not met within 30s" }
                Thread.sleep(250)
            }
        }
    }
}
