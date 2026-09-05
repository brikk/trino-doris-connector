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
import io.trino.testing.MaterializedRow
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/**
 * P5 remote statistics: probe pins for what Doris 4.1.3 exposes (pre/post ANALYZE, formats,
 * permissions) + live `getTableStatistics` behavior through `SHOW STATS FOR` / EXPLAIN.
 * Probe findings table: `dev-docs/NOTES-p5-statistics.md`. Test setup runs ANALYZE via ROOT
 * (never the connector — statistics writes are the user's domain) so results are
 * deterministic on a fresh cluster regardless of auto-analyze timing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisP5Statistics : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    /** SHOW STATS rows keyed by column name; the summary row keys as "" (null column_name). */
    private fun showStats(table: String, session: Session = getSession()): Map<String, MaterializedRow> =
        computeActual(session, "SHOW STATS FOR $table").materializedRows
            .associateBy { (it.getField(0) as String?) ?: "" }

    // --- probe pins: the raw Doris statistics surface (root JDBC, no connector) ---

    @Test
    fun testProbePinStatsSurfacePrePostAnalyze() {
        // DROP STATS -> deterministic never-analyzed state: SHOW COLUMN CACHED STATS empty
        DorisTestCluster.executeAsRoot("DROP STATS p5_stats.bare")
        assertThat(DorisTestCluster.querySingleColumn("SHOW COLUMN CACHED STATS p5_stats.bare")).isEmpty()
        // TABLE_ROWS right after load lags the tablet report (reads 0) — the staleness pin
        // that forces the max(TABLE_ROWS, analyze-count) row-count strategy and the
        // 0-means-unknown policy (a fresh CI cluster hits exactly this window)
        val tableRows = DorisTestCluster.queryScalar(
            "SELECT TABLE_ROWS FROM information_schema.tables WHERE TABLE_SCHEMA = 'p5_stats' AND TABLE_NAME = 'bare'",
        )!!.toLong()
        assertThat(tableRows).isBetween(0L, 1000L) // 0 while stale, 1000 once the report lands
        // ANALYZE WITH SYNC populates exact counts immediately (independent of the report)
        DorisTestCluster.executeAsRoot("ANALYZE TABLE p5_stats.bare WITH SYNC")
        openRoot().use { connection ->
            connection.createStatement().executeQuery("SHOW COLUMN CACHED STATS p5_stats.bare").use { rs ->
                val countsByColumn = HashMap<String, Double>()
                while (rs.next()) {
                    countsByColumn[rs.getString("column_name")] = rs.getString("count").toDouble()
                }
                assertThat(countsByColumn).containsEntry("id", 1000.0).containsEntry("v", 1000.0)
            }
        }
        // restore the ANALYZE'd state consumed by later tests? no — 'bare' must stay
        // stats-less for testAbsentStatisticsDegradeToUnknownNeverFail, which re-drops
        DorisTestCluster.executeAsRoot("DROP STATS p5_stats.bare")
    }

    @Test
    fun testProbePinMinMaxTextFormats() {
        // the format pins that justify which min/max reach Trino as ranges (see reader KDoc)
        openRoot().use { connection ->
            connection.createStatement().executeQuery("SHOW COLUMN CACHED STATS p5_stats.t").use { rs ->
                val minMax = HashMap<String, Pair<String?, String?>>()
                while (rs.next()) {
                    minMax[rs.getString("column_name")] = rs.getString("min") to rs.getString("max")
                }
                assertThat(minMax["id"]).isEqualTo("1" to "6") // plain integer text
                assertThat(minMax["li"]).isEqualTo(
                    "-170141183460469231731687303715884105727" to "170141183460469231731687303715884105727",
                ) // LARGEINT extremes as plain text — parseable, range fed
                assertThat(minMax["dt"]!!.first).isEqualTo("'2000-01-01 00:00:00.000000'") // QUOTED -> never fed as range
                assertThat(minMax["f"]!!.second).isEqualTo("NaN") // float NaN -> never fed as range
            }
        }
    }

    @Test
    fun testProbePinTrinoRoCanReadEveryStatisticsSource() {
        DriverManager.getConnection("${DorisQueryRunner.JDBC_URL}/?user=trino_ro").use { connection ->
            val statement = connection.createStatement()
            statement.executeQuery(
                "SELECT TABLE_ROWS FROM information_schema.tables WHERE TABLE_SCHEMA = 'p5_stats' AND TABLE_NAME = 't'",
            ).use { rs -> assertThat(rs.next()).isTrue() }
            statement.executeQuery("SHOW TABLE STATS p5_stats.t").use { rs -> assertThat(rs.next()).isTrue() }
            statement.executeQuery("SHOW COLUMN CACHED STATS p5_stats.t").use { rs -> assertThat(rs.next()).isTrue() }
            statement.executeQuery("SELECT count FROM __internal_schema.column_statistics LIMIT 1")
                .use { rs -> assertThat(rs.next()).isTrue() }
        }
    }

    // --- getTableStatistics through the engine ---

    @Test
    fun testRowCountReachesTheOptimizer() {
        // 1M-row table: exact row count (ANALYZE'd in setup — deterministic on fresh clusters)
        val nums = showStats("doris.p0_probe.nums")
        assertThat(nums[""]!!.getField(4)).isEqualTo(1_001_000.0)
        // and the engine consumes it: EXPLAIN carries the estimate
        val plan = computeActual("EXPLAIN SELECT n FROM doris.p0_probe.nums").onlyValue as String
        assertThat(plan).contains("rows: 1001000")
        // small table
        assertThat(showStats("doris.p5_stats.t")[""]!!.getField(4)).isEqualTo(6.0)
    }

    @Test
    fun testColumnStatisticsFidelity() {
        val stats = showStats("doris.p5_stats.t")
        // SHOW STATS columns: 0=column_name 1=data_size 2=distinct_values_count 3=nulls_fraction 4=row_count 5=low 6=high
        val id = stats["id"]!!
        assertThat(id.getField(2)).isEqualTo(6.0) // ndv
        assertThat(id.getField(3)).isEqualTo(0.0) // nulls fraction
        assertThat(id.getField(5)).isEqualTo("1") // low from range
        assertThat(id.getField(6)).isEqualTo("6")
        val b = stats["b"]!!
        assertThat(b.getField(3) as Double).isEqualTo(2.0 / 6.0) // 2 NULLs of 6
        assertThat(b.getField(5)).isEqualTo("-9223372036854775808") // bigint extremes parse
        val v = stats["v"]!!
        assertThat(v.getField(2) as Double).isLessThanOrEqualTo(4.0) // ndv capped at non-null rows (4)
        assertThat(v.getField(1) as Double).isGreaterThan(0.0) // data size supplied
        assertThat(v.getField(5)).isNull() // no varchar range (quoted text never parsed)
        assertThat(stats["dt"]!!.getField(5)).isNull() // temporal ranges deliberately skipped
        assertThat(stats["f"]!!.getField(5)).isNull() // NaN max -> whole range dropped
        val dc = stats["dc"]!!
        assertThat(dc.getField(5)).isEqualTo("-9999999.99") // decimal range parses
        val li = stats["li"]!!
        assertThat(li.getField(5)).isNotNull() // LARGEINT (decimal(38,0)) range fed
    }

    @Test
    fun testAllNullColumnStatisticsRemainKnown() {
        DorisTestCluster.executeAsRoot(
            """
            CREATE TABLE p5_stats.all_null (id INT NOT NULL, v BIGINT)
            DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
            """.trimIndent(),
            "INSERT INTO p5_stats.all_null VALUES (1, NULL), (2, NULL), (3, NULL)",
            "ANALYZE TABLE p5_stats.all_null WITH SYNC",
        )
        val stats = showStats("doris.p5_stats.all_null")
        assertThat(stats[""]!!.getField(4)).isEqualTo(3.0)
        val v = stats["v"]!!
        assertThat(v.getField(2)).isEqualTo(0.0)
        assertThat(v.getField(3)).isEqualTo(1.0)
        val id = stats["id"]!!
        assertThat(id.getField(2)).isEqualTo(3.0)
        assertThat(id.getField(3)).isEqualTo(0.0)
        assertThat(id.getField(5)).isEqualTo("1")
        assertThat(id.getField(6)).isEqualTo("3")
    }

    @Test
    fun testRollupStatisticsDoNotReplaceBaseIndexStatistics() {
        DorisTestCluster.executeAsRoot(
            """
            CREATE TABLE p5_stats.rollups (k1 INT NOT NULL, k2 INT NOT NULL, v BIGINT SUM)
            AGGREGATE KEY(k1, k2) DISTRIBUTED BY HASH(k1) BUCKETS 1 PROPERTIES ("replication_num" = "1")
            """.trimIndent(),
            "INSERT INTO p5_stats.rollups VALUES (1, 1, 6), (1, 2, 7), (1, 3, 15), (2, 1, 5), (3, 1, 9)",
            "ALTER TABLE p5_stats.rollups ADD ROLLUP r_k1(k1, v)",
        )
        openRoot().use { connection ->
            connection.setNetworkTimeout(Runnable::run, 10_000)
            connection.createStatement().use { statement ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
                while (true) {
                    val state = statement.executeQuery(
                        "SHOW ALTER TABLE ROLLUP FROM p5_stats WHERE TableName = 'rollups'",
                    ).use { rs ->
                        check(rs.next())
                        rs.getString("State")
                    }
                    assertThat(state).describedAs("rollups.r_k1 build state").isNotEqualTo("CANCELLED")
                    if (state == "FINISHED") {
                        break
                    }
                    check(System.nanoTime() < deadline) { "rollups.r_k1 did not finish within 120s: $state" }
                    Thread.sleep(250)
                }
            }
        }
        DorisTestCluster.executeAsRoot("ANALYZE TABLE p5_stats.rollups WITH SYNC")
        openRoot().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SHOW COLUMN CACHED STATS p5_stats.rollups (v)").use { rs ->
                    // SHOW order is unspecified; the base index is named after the remote table, not N/A.
                    val vStatsByIndex = HashMap<String, List<Double>>()
                    while (rs.next()) {
                        vStatsByIndex[rs.getString("index_name")] = listOf(
                            rs.getDouble("count"), rs.getDouble("ndv"), rs.getDouble("data_size"), rs.getDouble("min"), rs.getDouble("max"),
                        )
                    }
                    assertThat(vStatsByIndex).containsOnlyKeys("rollups", "r_k1")
                    assertThat(vStatsByIndex["rollups"]).containsExactly(5.0, 5.0, 40.0, 5.0, 15.0)
                    assertThat(vStatsByIndex["r_k1"]).containsExactly(3.0, 3.0, 24.0, 5.0, 28.0)
                }
            }
        }
        val stats = showStats("doris.p5_stats.rollups")
        assertThat(stats[""]!!.getField(4)).isEqualTo(5.0)
        val v = stats["v"]!!
        // Trino SHOW STATS omits data size for fixed-width types; the reader unit pins it.
        assertThat(v.getField(1)).isNull()
        assertThat(v.getField(2)).isEqualTo(5.0)
        assertThat(v.getField(3)).isEqualTo(0.0)
        assertThat(v.getField(5)).isEqualTo("5")
        assertThat(v.getField(6)).isEqualTo("15")
    }

    @Test
    fun testAbsentStatisticsDegradeToUnknownNeverFail() {
        // never-analyzed + report-lagged table: everything unknown, query still runs
        DorisTestCluster.executeAsRoot("DROP STATS p5_stats.bare")
        val bare = showStats("doris.p5_stats.bare")
        // row_count may be known (1000) once the tablet report lands, or unknown while it
        // lags — but it must NEVER be an exact 0 for this loaded table
        assertThat(bare[""]!!.getField(4)).isNotEqualTo(0.0)
        assertThat(computeActual("SELECT count(*) FROM doris.p5_stats.bare").onlyValue).isEqualTo(1000L)
        // Doris view: no statistics surface at all — degrades silently
        val view = showStats("doris.p0_probe.scalars_view")
        assertThat(view[""]!!.getField(4)).isNull()
        assertThat(computeActual("SELECT count(*) FROM doris.p0_probe.scalars_view").onlyValue).isEqualTo(8L)
    }

    @Test
    fun testStatisticsCanBeDisabledPerSession() {
        val disabled = Session.builder(session)
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "statistics_enabled", "false")
            .build()
        assertThat(showStats("doris.p0_probe.nums", disabled)[""]!!.getField(4)).isNull()
        // and back on (default true from doris.statistics.enabled)
        assertThat(showStats("doris.p0_probe.nums")[""]!!.getField(4)).isEqualTo(1_001_000.0)
    }

    private fun openRoot() = DorisTestCluster.openRootConnection()

    companion object {
        private fun provisionFixture() {
            DorisFixtures.ensureBaseFixtures() // setup ANALYZEs p0_probe.nums below
            DorisTestCluster.executeAsRoot(
                "DROP DATABASE IF EXISTS p5_stats",
                "CREATE DATABASE p5_stats",
                """
                CREATE TABLE p5_stats.t (
                    id INT NOT NULL, b BIGINT, v VARCHAR(50), d DATE, dt DATETIME(6),
                    li LARGEINT, dc DECIMAL(9,2), f FLOAT, dbl DOUBLE
                ) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                INSERT INTO p5_stats.t VALUES
                (1, -9223372036854775808, 'alpha', '2000-01-01', '2000-01-01 00:00:00', -170141183460469231731687303715884105727, -9999999.99, 1.5, 2.5),
                (2, 9223372036854775807, 'beta', '2021-06-15', '2021-06-15 12:34:56.789012', 170141183460469231731687303715884105727, 9999999.99, cast('nan' AS FLOAT), 3.5),
                (3, 0, 'beta', '2010-01-01', '2010-01-01 01:01:01', 0, 0.00, 0.5, NULL),
                (4, 5, 'gamma', NULL, NULL, NULL, NULL, NULL, NULL),
                (5, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
                (6, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)
                """.trimIndent(),
                "CREATE TABLE p5_stats.bare (id INT NOT NULL, v VARCHAR(20)) " +
                    "DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES (\"replication_num\" = \"1\")",
                "INSERT INTO p5_stats.bare SELECT number, concat('v', number) FROM numbers('number' = '1000')",
                // deterministic stats for the assertion tables — via ROOT, never the connector
                "ANALYZE TABLE p5_stats.t WITH SYNC",
                "ANALYZE TABLE p0_probe.nums WITH SYNC",
                "DROP STATS p5_stats.bare",
                // trino_ro permission pin needs the account (idempotent recreate, same
                // statements as TestDorisTrinoRoAccount)
                "DROP USER IF EXISTS 'trino_ro'@'%'",
                "CREATE USER 'trino_ro'@'%' IDENTIFIED BY ''",
                "GRANT SELECT_PRIV ON internal.*.* TO 'trino_ro'@'%'",
            )
        }
    }
}
