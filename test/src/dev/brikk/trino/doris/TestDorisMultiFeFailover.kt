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

import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIf
import java.net.InetSocketAddress
import java.net.Socket
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Multi-FE FAILOVER evidence, guarded to the OPTIONAL 3-FE overlay
 * (`compose/multi-fe`, project `trino-doris-mfe`, host MySQL ports 9131/9132/9133).
 *
 * This test is SEPARATE from the default single-FE probe cluster (port 9130) that CI
 * and every other live suite use. It is @EnabledIf-guarded on [overlayIsUp]: when the
 * overlay is DOWN (the CI default — CI never starts it) the whole class is DISABLED and
 * skipped cleanly, so `./gradlew build` is never broken by its absence. When the overlay
 * IS up (a dev has run `compose/multi-fe/up.sh`) it asserts:
 *
 *   1. the connector connects + queries through a MULTI-HOST comma-list URL
 *      (`jdbc:mysql://h:9131,h:9132,h:9133/`) — the production-shaped failover URL; and
 *   2. NEW-connection failover: after a NON-MASTER follower FE is stopped via docker,
 *      fresh connections via the multi-host URL (both raw Connector/J and through the
 *      connector) still succeed on the surviving FEs. The stopped follower is always
 *      restarted in [restartStoppedFollower] (@AfterEach), so the overlay is left intact.
 *
 * The DESTRUCTIVE master-kill / re-election-timing evidence is deliberately NOT automated
 * here (it is slow — ~60s BDBJE re-election on a hard crash — and perturbs the shared
 * overlay); it lives as a scripted, reproducible procedure with recorded output in
 * dev-docs/REPORT-multi-fe-failover.md.
 *
 * All docker + JDBC helpers are SELF-CONTAINED in this file (companion object) by design:
 * the shared DorisTestCluster/DorisQueryRunner/DorisFixtures utilities are hard-wired to
 * the single-FE cluster (port 9130) and must not be modified for this overlay work. The
 * connector runner is built through the PUBLIC [DorisQueryRunner.dynamicCatalogBuilder]
 * API and pointed at the overlay purely via a `CREATE CATALOG ... USING doris` statement.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation::class)
@EnabledIf("dev.brikk.trino.doris.TestDorisMultiFeFailover#overlayIsUp")
class TestDorisMultiFeFailover : AbstractTestQueryFramework() {
    /** Container stopped by a failover test, to be restarted in @AfterEach. Null when none. */
    private var stoppedContainer: String? = null

    override fun createQueryRunner(): QueryRunner {
        // dynamicCatalogBuilder installs the plugin but creates NO static catalog and sets no
        // default schema; the overlay catalog is created per-test via SQL against the
        // multi-host URL. (The builder still runs DorisFixtures against the single-FE cluster
        // — harmless, idempotent, and that cluster is up whenever the overlay is.)
        return DorisQueryRunner.dynamicCatalogBuilder().build()
    }

    @AfterEach
    fun restartStoppedFollower() {
        val container = stoppedContainer ?: return
        stoppedContainer = null
        dockerStart(container)
        // Wait for the restarted follower to rejoin + accept SQL so we never leave the overlay
        // degraded for the next test / for the developer.
        awaitFollowerAlive(container)
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    fun testConnectorConnectsAndQueriesThroughMultiHostUrl() {
        createOverlayCatalog()
        try {
            // Connect + query through the connector over the comma-list multi-host URL.
            assertThat(query("SELECT 1")).matches("VALUES 1")
            // Metadata read proves the connector talks to a real Doris FE via the URL, not a stub.
            assertThat(computeActual("SHOW SCHEMAS FROM $OVERLAY_CATALOG").onlyColumnAsSet)
                .contains("information_schema")
        } finally {
            dropOverlayCatalog()
        }
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    fun testNewConnectionFailoverAfterFollowerStops() {
        // Sanity: raw multi-host connect works before we perturb anything.
        assertThat(multiHostSelectOne()).isEqualTo(1)

        // Pick a NON-MASTER follower to stop (stopping the master would trigger a slow
        // re-election and is kept as manual REPORT evidence, not automated here).
        val followerContainer = pickNonMasterFollowerContainer()
        stoppedContainer = followerContainer // ensure @AfterEach restarts it even if we throw
        dockerStop(followerContainer)

        // NEW raw connections via the multi-host URL must still succeed on the surviving FEs.
        // (Connector/J blacklists the dead host after the first failure; we retry briefly to
        // ride out the transient docker-proxy window right after stop.)
        assertThat(awaitMultiHostSelectOne()).isEqualTo(1)

        // ...and NEW connections THROUGH THE CONNECTOR succeed too (a fresh catalog opens fresh
        // pooled connections against the same multi-host URL).
        createOverlayCatalog()
        try {
            assertThat(query("SELECT 1")).matches("VALUES 1")
            assertThat(computeActual("SHOW SCHEMAS FROM $OVERLAY_CATALOG").onlyColumnAsSet)
                .contains("information_schema")
        } finally {
            dropOverlayCatalog()
        }
    }

    /**
     * THE test this whole fix exists for, deterministic form: a query OWNED by fe2 killed
     * through a helper connected to fe3 — the FE forwards `KILL QUERY "<QueryId>"` to the
     * owner. This is the cross-FE leg the connector's cluster-scoped cancel relies on
     * ([DorisClusterScopedCancel]); the driver-level `KILL QUERY <connection-id>` this
     * replaces is a silent no-op / wrong-kill in exactly this topology.
     */
    @Test
    @org.junit.jupiter.api.Order(2)
    fun testCrossFeKillByQueryIdReleasesForeignFeQuery() {
        val marker = "trino_query_id=mfe_xfe_${System.nanoTime()}"
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val victim = pool.submit<String> {
            runCatching {
                // victimProps() uses a socketTimeout LONGER than the 20s kill-wait window below
                // (props()'s 8s would race the kill and mask the server message with a client-side
                // "Communications link failure"); sleep(60) can only be unblocked by the KILL.
                DriverManager.getConnection("jdbc:mysql://$HOST:9132/", victimProps()).use {
                    it.createStatement().execute("SELECT sleep(60) /*$marker*/")
                }
            }.exceptionOrNull()?.message ?: "completed"
        }
        try {
            DriverManager.getConnection("jdbc:mysql://$HOST:9133/", props()).use { helper ->
                helper.createStatement().use { statement ->
                    runCatching { statement.execute("SET fetch_all_fe_for_system_table = true") }
                    var queryId: String? = null
                    var ownerFe: String? = null
                    awaitCondition("fe2-owned statement visible from fe3") {
                        statement.executeQuery("SHOW FULL PROCESSLIST").use { rs ->
                            while (rs.next()) {
                                val info = rs.getString("Info") ?: continue
                                if (info.contains(marker) && !info.contains("PROCESSLIST")) {
                                    queryId = rs.getString("QueryId")
                                    ownerFe = rs.getString("FE")
                                }
                            }
                        }
                        queryId != null
                    }
                    // the query is genuinely FOREIGN to the helper's FE
                    assertThat(ownerFe).isEqualTo("172.30.82.11") // fe2
                    statement.execute("""KILL QUERY "$queryId"""")
                    // the blocked victim read unblocks with the server's kill error
                    assertThat(victim.get(20, TimeUnit.SECONDS)).contains("cancel query by user")
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * End-to-end: the CONNECTOR cancels a query running against the multi-FE overlay through
     * the comma-list URL — the cluster-scoped kill dispatch fires (same-JVM counter) and the
     * Doris-side work is released cluster-wide.
     */
    @Test
    @org.junit.jupiter.api.Order(3)
    fun testConnectorCancellationReleasesOverlayQuery() {
        provisionCancelFixture()
        createOverlayCatalog()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val slow = "SELECT count(*) AS mfe_cancel_probe FROM $OVERLAY_CATALOG.mfe_cancel.big a " +
                "CROSS JOIN $OVERLAY_CATALOG.mfe_cancel.big b WHERE a.pad = b.pad AND a.id + b.id = -1"
            val future = pool.submit<Any> { runCatching { queryRunner.execute(slow) } }
            var trinoQueryId: String? = null
            awaitCondition("running Trino query") {
                if (future.isDone) {
                    val outcome = future.get()
                    error("slow overlay query ended before cancel: " + outcome)
                }
                trinoQueryId = computeActual(
                    "SELECT query_id FROM system.runtime.queries WHERE state = 'RUNNING' " +
                        "AND query LIKE '%mfe_cancel_probe%' AND query NOT LIKE '%system.runtime%'",
                ).onlyColumnAsSet.map { it.toString() }.firstOrNull()
                trinoQueryId != null
            }
            val remoteMarker = DorisRemoteQueryModifier.marker(trinoQueryId!!)
            awaitCondition("overlay processlist entry") { overlayHasRunningStatement(remoteMarker) }

            val dispatchedBefore = DorisClusterScopedCancel.killsDispatched.get()
            queryRunner.execute("CALL system.runtime.kill_query(query_id => '$trinoQueryId', message => 'mfe cancel test')")
            // the primary cluster-scoped dispatch fired...
            awaitCondition("cluster-scoped dispatch") { DorisClusterScopedCancel.killsDispatched.get() > dispatchedBefore }
            // ...and the overlay's work is released (cluster-wide view)
            awaitCondition("overlay release") { !overlayHasRunningStatement(remoteMarker) }
            future.cancel(true)
        } finally {
            pool.shutdownNow()
            dropOverlayCatalog()
        }
    }

    private fun overlayHasRunningStatement(marker: String): Boolean =
        openOverlayConnection().use { connection ->
            connection.createStatement().use { statement ->
                runCatching { statement.execute("SET fetch_all_fe_for_system_table = true") }
                statement.executeQuery("SHOW FULL PROCESSLIST").use { rs ->
                    generateSequence { if (rs.next()) (rs.getString("Info") ?: "") else null }
                        .any { it.contains(marker) && !it.contains("PROCESSLIST") }
                }
            }
        }

    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for: " + what }
            Thread.sleep(500)
        }
    }

    private fun provisionCancelFixture() {
        openOverlayConnection().use { connection ->
            connection.createStatement().use { statement ->
                val count = runCatching {
                    statement.executeQuery("SELECT COUNT(*) FROM mfe_cancel.big").use { rs -> rs.next(); rs.getLong(1) }
                }.getOrNull()
                if (count == 200_000L) {
                    return
                }
                statement.execute("CREATE DATABASE IF NOT EXISTS mfe_cancel")
                statement.execute("DROP TABLE IF EXISTS mfe_cancel.big")
                statement.execute(
                    "CREATE TABLE mfe_cancel.big (id BIGINT NOT NULL, pad VARCHAR(400)) " +
                        "DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES (\"replication_num\" = \"1\")",
                )
                statement.execute("INSERT INTO mfe_cancel.big SELECT number, repeat('x', 300) FROM numbers('number' = '200000')")
            }
        }
    }

    private fun createOverlayCatalog() {
        assertUpdate(
            """
            CREATE CATALOG $OVERLAY_CATALOG USING doris
            WITH (
                "connection-url" = '$MULTI_HOST_URL',
                "connection-user" = '$USER'
            )
            """.trimIndent(),
        )
    }

    private fun dropOverlayCatalog() {
        if (computeActual("SHOW CATALOGS").onlyColumnAsSet.contains(OVERLAY_CATALOG)) {
            assertUpdate("DROP CATALOG $OVERLAY_CATALOG")
        }
    }

    /** Choose an alive follower FE that is NOT the current master; map its IP to its container. */
    private fun pickNonMasterFollowerContainer(): String {
        val host = showFrontends().firstOrNull { !it.isMaster && it.alive }?.host
            ?: error("no alive non-master follower found in the overlay")
        return IP_TO_CONTAINER[host] ?: error("unknown overlay FE host $host (not in $IP_TO_CONTAINER)")
    }

    /** A row of `SHOW FRONTENDS` reduced to the columns the failover pick needs. */
    private data class FrontendRow(val host: String, val isMaster: Boolean, val alive: Boolean)

    private fun showFrontends(): List<FrontendRow> {
        openOverlayConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SHOW FRONTENDS").use { resultSet ->
                    val meta = resultSet.metaData
                    val cols = (1..meta.columnCount).associateBy { meta.getColumnLabel(it).lowercase() }
                    val hostCol = requireNotNull(cols["host"]) { "SHOW FRONTENDS missing Host" }
                    val masterCol = requireNotNull(cols["ismaster"]) { "SHOW FRONTENDS missing IsMaster" }
                    val aliveCol = requireNotNull(cols["alive"]) { "SHOW FRONTENDS missing Alive" }
                    val rows = ArrayList<FrontendRow>()
                    while (resultSet.next()) {
                        rows.add(
                            FrontendRow(
                                host = resultSet.getString(hostCol),
                                isMaster = resultSet.getString(masterCol).equals("true", ignoreCase = true),
                                alive = resultSet.getString(aliveCol).equals("true", ignoreCase = true),
                            ),
                        )
                    }
                    return rows
                }
            }
        }
    }

    companion object {
        private const val HOST = "127.0.0.1"
        private val FE_PORTS = intArrayOf(9131, 9132, 9133)
        private const val USER = "root"
        private const val OVERLAY_CATALOG = "doris_mfe"

        /** The production-shaped multi-host failover URL: plain comma-list of every FE. */
        private val MULTI_HOST_URL =
            "jdbc:mysql://" + FE_PORTS.joinToString(",") { "$HOST:$it" } + "/"

        /** Overlay FE static IP (docker-compose.yml) -> container name, for docker stop/start. */
        private val IP_TO_CONTAINER = mapOf(
            "172.30.82.10" to "trino-doris-mfe-fe1",
            "172.30.82.11" to "trino-doris-mfe-fe2",
            "172.30.82.12" to "trino-doris-mfe-fe3",
        )

        /**
         * @EnabledIf guard: the overlay is considered UP iff ALL THREE FE MySQL host ports
         * accept a TCP connection. When any is down the whole class is skipped cleanly, so a
         * default `./gradlew build` (overlay not started) never fails on account of this suite.
         */
        @JvmStatic
        fun overlayIsUp(): Boolean = FE_PORTS.all { tcpReachable(HOST, it) }

        private fun tcpReachable(host: String, port: Int): Boolean =
            runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), 750); true }
            }.getOrDefault(false)

        private fun props(): Properties = Properties().apply {
            setProperty("user", USER)
            setProperty("connectTimeout", "3000")
            setProperty("socketTimeout", "8000")
        }

        /**
         * For the cross-FE-kill victim: a socketTimeout comfortably longer than the 20s window
         * the test waits for the kill, and shorter than the query's own sleep(60). This makes the
         * KILL the ONLY thing that can unblock the victim inside the window, so the assertion sees
         * the server's "cancel query by user" message rather than a racing client-side timeout.
         */
        private fun victimProps(): Properties = Properties().apply {
            setProperty("user", USER)
            setProperty("connectTimeout", "3000")
            setProperty("socketTimeout", "40000")
        }

        private fun openOverlayConnection(): Connection =
            DriverManager.getConnection(MULTI_HOST_URL, props())

        /** One `SELECT 1` over a fresh raw multi-host connection; returns the scalar. */
        private fun multiHostSelectOne(): Int {
            openOverlayConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { resultSet ->
                        check(resultSet.next())
                        return resultSet.getInt(1)
                    }
                }
            }
        }

        /**
         * Like [multiHostSelectOne] but tolerant of the brief transient right after a docker
         * stop (docker-proxy may still hold the dead host's port, hanging the first attempt
         * until connectTimeout). Retries new connections for up to ~30s.
         */
        private fun awaitMultiHostSelectOne(): Int {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var last: Exception? = null
            while (System.nanoTime() < deadline) {
                try {
                    return multiHostSelectOne()
                } catch (e: Exception) {
                    last = e
                    Thread.sleep(1000)
                }
            }
            throw AssertionError("multi-host SELECT 1 never succeeded after a follower stop", last)
        }

        private fun awaitFollowerAlive(container: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (System.nanoTime() < deadline) {
                val ok = runCatching {
                    docker("exec", container, "mysql", "-h127.0.0.1", "-P9030", "-uroot", "-e", "SELECT 1")
                }.getOrDefault(1) == 0
                if (ok) return
                Thread.sleep(2000)
            }
            error("overlay follower $container did not come back alive within 120s")
        }

        private fun dockerStop(container: String) {
            check(docker("stop", container) == 0) { "docker stop $container failed" }
        }

        private fun dockerStart(container: String) {
            check(docker("start", container) == 0) { "docker start $container failed" }
        }

        /** Run a docker CLI command; return its exit code. Output is discarded. */
        private fun docker(vararg args: String): Int {
            val process = ProcessBuilder(listOf("docker", *args))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            check(process.waitFor(150, TimeUnit.SECONDS)) { "docker ${args.joinToString(" ")} timed out" }
            return process.exitValue()
        }
    }
}
