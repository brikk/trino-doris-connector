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

import io.trino.Session
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Live cancellation contract (ledger §D): killing the Trino query must promptly release the
 * Doris work. Evidence baseline: `Statement.cancel()` returns in ~209 ms, `KILL QUERY` clears
 * the FE processlist in ~18 ms, end-to-end via stock Trino the Doris scan vanished within ~1 s
 * (PROBE §8; STOCK). CI-safe bounds below; measured numbers are printed.
 *
 * The slow query streams `p1_cancel.big` (~500 MB on the wire) into a deliberately slow Trino
 * cross join, so the remote Doris statement stays genuinely RUNNING (send-blocked) — small
 * scans complete server-side into buffers immediately and are not cancellable-in-flight
 * (proven by the P1b timeout probe). Remote statements are identified in
 * `information_schema.processlist` by the `trino_query_id=` comment — no heuristics.
 *
 * Tests are ordered and clean up their Trino query via kill_query even on failure, so a
 * failing test cannot leave a CPU-burning zombie query that starves the next one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TestDorisCancellation : AbstractTestQueryFramework() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun createQueryRunner(): QueryRunner {
        provisionBigFixture()
        return DorisQueryRunner.builder().build()
    }

    @AfterAll
    fun shutdownExecutor() {
        executor.shutdownNow()
    }

    @Test
    @Order(1)
    fun testKillQueryReleasesDorisWork() {
        val queryFuture = submitSlowQuery(getSession(), KILL_MARKER)
        try {
            // 1. The Trino query is running and its remote statement is live on the Doris FE.
            val trinoQueryId = awaitRunningTrinoQueryId(KILL_MARKER)
            val remoteMarker = DorisRemoteQueryModifier.marker(trinoQueryId)
            await("Doris processlist entry for $remoteMarker", APPEAR_BOUND_MILLIS) {
                DorisTestCluster.runningStatements().any { it.contains(remoteMarker) }
            }

            // 2. Kill the Trino query; the Doris query must leave the processlist promptly.
            val killStart = System.nanoTime()
            getQueryRunner().execute(
                getSession(),
                "CALL system.runtime.kill_query(query_id => '$trinoQueryId', message => 'p1b cancellation test')",
            )
            await("Doris processlist clearance of $remoteMarker", CLEARANCE_BOUND_MILLIS) {
                DorisTestCluster.runningStatements().none { it.contains(remoteMarker) }
            }
            val clearanceMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - killStart)
            println("Doris processlist cleared ${clearanceMillis}ms after kill_query (ledger §D baseline ~1s, bound ${CLEARANCE_BOUND_MILLIS}ms)")

            // 3. The Trino side failed with the kill message.
            val result = queryFuture.get(30, TimeUnit.SECONDS)
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).hasMessageContaining("p1b cancellation test")
        } finally {
            cleanUp(KILL_MARKER, queryFuture)
        }
    }

    @Test
    @Order(2)
    fun testQueryTimeoutSessionPropertyAbortsTheDorisQuery() {
        // SET SESSION doris.query_timeout applies `SET query_timeout` server-side on the scan
        // connection; the Doris timeout checker kills the still-running (send-blocked) query
        // ("query is timeout, killed by timeout checker" — P1b probe; PROBE §8/§9). The
        // checker sweeps periodically, hence the generous release bound; measured ~9-11s for
        // a 2s timeout.
        val session: Session = Session.builder(getSession())
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, DorisSessionProperties.QUERY_TIMEOUT, "2.00s")
            .build()
        val queryFuture = submitSlowQuery(session, TIMEOUT_MARKER)
        try {
            val trinoQueryId = awaitRunningTrinoQueryId(TIMEOUT_MARKER)
            val remoteMarker = DorisRemoteQueryModifier.marker(trinoQueryId)
            val start = System.nanoTime()
            await("Doris processlist entry for $remoteMarker", APPEAR_BOUND_MILLIS) {
                DorisTestCluster.runningStatements().any { it.contains(remoteMarker) }
            }
            // The Doris-side work must be RELEASED by the server-side timeout — that is the
            // contract; the Trino-side failure propagates later, whenever the stalled scan
            // next reads from the killed connection.
            await("Doris-side release by the timeout checker", TIMEOUT_RELEASE_BOUND_MILLIS) {
                DorisTestCluster.runningStatements().none { it.contains(remoteMarker) }
            }
            val releaseMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            println("doris.query_timeout=2s released the Doris work after ${releaseMillis}ms (bound ${TIMEOUT_RELEASE_BOUND_MILLIS}ms)")

            val result = queryFuture.get(TRINO_FAILURE_BOUND_MILLIS, TimeUnit.MILLISECONDS)
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).hasMessageMatching("(?is).*timeout.*")
        } finally {
            cleanUp(TIMEOUT_MARKER, queryFuture)
        }
    }

    private fun submitSlowQuery(session: Session, marker: String): Future<Result<Any>> =
        executor.submit(Callable { runCatching { getQueryRunner().execute(session, slowQuery(marker)) } })

    /** Fail-safe: kill any still-running slow query so a failing test cannot starve the next. */
    private fun cleanUp(marker: String, queryFuture: Future<*>) {
        runCatching {
            for (queryId in runningTrinoQueryIds(marker)) {
                getQueryRunner().execute(
                    getSession(),
                    "CALL system.runtime.kill_query(query_id => '$queryId', message => 'test cleanup')",
                )
            }
        }
        queryFuture.cancel(true)
    }

    private fun runningTrinoQueryIds(marker: String): List<String> =
        computeActual(
            "SELECT query_id FROM system.runtime.queries WHERE state = 'RUNNING' AND query LIKE '%$marker%' AND query NOT LIKE '%system.runtime%'",
        ).onlyColumnAsSet.map { it.toString() }

    private fun awaitRunningTrinoQueryId(marker: String): String {
        var queryId: String? = null
        await("running Trino query id for $marker", APPEAR_BOUND_MILLIS) {
            queryId = runningTrinoQueryIds(marker).firstOrNull()
            queryId != null
        }
        return queryId!!
    }

    private fun await(what: String, boundMillis: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundMillis)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out after ${boundMillis}ms waiting for: $what" }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    companion object {
        private const val KILL_MARKER = "p1b_kill_probe"
        private const val TIMEOUT_MARKER = "p1b_timeout_probe"

        /**
         * length(pad) keeps the 500-byte pad column in the remote scan (no expression
         * pushdown), and the 100k-row build side makes the Trino-side cross-join evaluation
         * slow enough (~10^11 combinations) that the streamed probe scan stays alive under
         * backpressure for minutes.
         */
        private fun slowQuery(marker: String): String =
            "SELECT count(*) AS $marker FROM doris.p1_cancel.big a " +
                "CROSS JOIN (SELECT n FROM doris.p0_probe.nums WHERE n < 100000) b WHERE length(a.pad) + a.id + b.n = -1"

        private const val APPEAR_BOUND_MILLIS = 30_000L
        private const val CLEARANCE_BOUND_MILLIS = 15_000L

        /** query_timeout=2s + timeout-checker sweep period + polling slack (measured 9-21s). */
        private const val TIMEOUT_RELEASE_BOUND_MILLIS = 60_000L
        private const val TRINO_FAILURE_BOUND_MILLIS = 120_000L
        private const val POLL_INTERVAL_MILLIS = 250L
        private const val BIG_ROWS = 1_001_000L

        /** Persistent fixture (cheap to build, ~1.5s): recreated only when missing/short. */
        private fun provisionBigFixture() {
            DorisFixtures.ensureBaseFixtures() // p1_cancel.big is derived from p0_probe.nums

            val existing = runCatching { DorisTestCluster.queryScalar("SELECT COUNT(*) FROM p1_cancel.big")?.toLong() }
                .getOrNull()
            if (existing == BIG_ROWS) {
                return
            }
            DorisTestCluster.executeAsRoot(
                "CREATE DATABASE IF NOT EXISTS p1_cancel",
                "DROP TABLE IF EXISTS p1_cancel.big",
                """
                CREATE TABLE p1_cancel.big (id BIGINT NOT NULL, pad VARCHAR(600))
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1
                PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                "INSERT INTO p1_cancel.big SELECT n, repeat('x', 500) FROM p0_probe.nums",
            )
        }
    }
}
