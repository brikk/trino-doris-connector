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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * P6 date-of-datetime projection pushdown + GROUP BY composition (the user's motivating
 * query: `GROUP BY date(event_at)` on a huge table -> ONE remote statement). Probe verdicts
 * in `NOTES-p6-date-projection.md`; evidence pins [DorisPushdownEvidence.DATE_OF_DATETIME]
 * / [DorisPushdownEvidence.DATE_TRUNC]. Differentials compare pushed results against the
 * unpushed plan (`complex_expression_pushdown = false` disables convertProjection wiring).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisP6DateProjection : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    private val local: io.trino.Session
        get() = io.trino.Session.builder(session)
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "complex_expression_pushdown", "false")
            .build()

    private fun rows(session: io.trino.Session, sql: String): List<String> =
        computeActual(session, sql).materializedRows.map { it.toString() }.sorted()

    private fun assertPushedAndIdentical(sql: String) {
        assertThat(query(sql)).describedAs(sql).isFullyPushedDown()
        assertThat(rows(session, sql)).describedAs(sql).isEqualTo(rows(local, sql))
    }

    private fun remoteSql(sql: String): String {
        val execution = getDistributedQueryRunner().executeWithPlan(session, sql)
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        return DorisTestCluster.awaitAuditLogStatements(marker, 60_000).single { it.contains("p6_dates") }
    }

    // --- projection-only pushdown, all three user spellings ---

    @Test
    fun testDateProjectionPushesInAllThreeSpellings() {
        // date(x) desugars to CAST(x AS DATE) — one rule covers both; date_trunc('day') is
        // the third spelling (returns timestamp, not date — still pushed)
        for (spelling in listOf("date(dt6)", "CAST(dt6 AS DATE)")) {
            val sql = "SELECT $spelling FROM doris.p6_dates.t"
            assertPushedAndIdentical(sql)
        }
        assertThat(remoteSql("SELECT date(dt6) FROM doris.p6_dates.t")).contains("CAST(`dt6` AS DATE)")
        assertPushedAndIdentical("SELECT date_trunc('day', dt6) FROM doris.p6_dates.t")
        assertThat(remoteSql("SELECT date_trunc('day', dt6) FROM doris.p6_dates.t"))
            .contains("date_trunc(`dt6`, 'day')")
        // every datetime precision
        for (column in listOf("dt0", "dt3", "dt6")) {
            assertPushedAndIdentical("SELECT date($column) FROM doris.p6_dates.t")
        }
    }

    // --- the motivating composition: GROUP BY date(...) + aggregates, ONE remote statement ---

    @Test
    fun testGroupByDateOfDatetimeFullyPushes() {
        // count/min compose (sum(BIGINT) is deliberately NOT pushable — P4 wrap divergence —
        // and would split the aggregation into partial/final instead)
        for (spelling in listOf("date(dt6)", "CAST(dt6 AS DATE)", "date_trunc('day', dt6)")) {
            val sql = "SELECT $spelling AS d, count(*), min(v) FROM doris.p6_dates.t GROUP BY 1"
            assertPushedAndIdentical(sql)
        }
        val statement = remoteSql("SELECT date(dt6) AS d, count(*), min(v) FROM doris.p6_dates.t GROUP BY 1")
        assertThat(statement).contains("CAST(`dt6` AS DATE)").contains("count(*)").contains("GROUP BY")
    }

    @Test
    fun testGroupByComposesWithPushedWhereAndLimit() {
        // LIMIT above the group count keeps the result deterministic (LIMIT n < groups
        // would be an arbitrary-subset comparison)
        val sql = "SELECT date(dt6) AS d, count(*) FROM doris.p6_dates.t " +
            "WHERE v > 0 AND dt6 >= TIMESTAMP '2026-01-01 00:00:00' GROUP BY 1 LIMIT 10"
        assertPushedAndIdentical(sql)
        val statement = remoteSql(sql)
        assertThat(statement).contains("CAST(`dt6` AS DATE)").contains("GROUP BY")
            .contains("`v` > 0").contains("LIMIT 10")
    }

    // --- date_trunc unit matrix ---

    @Test
    fun testDateTruncUnitMatrixDifferentials() {
        for (unit in listOf("second", "minute", "hour", "day", "month", "quarter", "year")) {
            val sql = "SELECT id, date_trunc('$unit', dt6) FROM doris.p6_dates.t"
            assertPushedAndIdentical(sql)
            assertPushedAndIdentical("SELECT date_trunc('$unit', dt6) AS g, count(*) FROM doris.p6_dates.t GROUP BY 1")
        }
    }

    @Test
    fun testDeniedShapesStayLocal() {
        // Doris rejects millisecond; week is the year-0 divergence — both stay local (correct)
        for (unit in listOf("millisecond", "week")) {
            val sql = "SELECT id, date_trunc('$unit', dt6) FROM doris.p6_dates.t"
            assertThat(query(sql)).describedAs(unit).isNotFullyPushedDown(
                io.trino.sql.planner.plan.ProjectNode::class.java,
            )
            assertThat(rows(session, sql)).describedAs(unit).isEqualTo(rows(local, sql))
        }
        // both engines are Monday-start on ordinary dates (Sunday 2026-07-19 -> 07-13) —
        // the DENIAL is purely about the year-0 clamp, pinned in testEdgeDates
        assertThat(computeActual("SELECT date_trunc('week', dt6) FROM doris.p6_dates.t WHERE id = 3").onlyValue.toString())
            .startsWith("2026-07-13")
        // non-constant unit stays local
        assertThat(query("SELECT date_trunc(u, dt6) FROM doris.p6_dates.t WHERE u IS NOT NULL"))
            .isNotFullyPushedDown(io.trino.sql.planner.plan.ProjectNode::class.java)
    }

    // --- edges ---

    @Test
    fun testEdgeDates() {
        // 0000-01-01 / 9999-12-31 date extraction: probe-identical, differential-pinned
        assertPushedAndIdentical("SELECT id, date(dt6) FROM doris.p6_dates.t WHERE id IN (1, 2)")
        // year-0 week edge: Trino truncates into year -1 (proleptic) while Doris CLAMPS to
        // 0000-01-01 — THE divergence that keeps 'week' out of the pushed unit allowlist:
        val trinoWeek = computeActual(local, "SELECT date_trunc('week', dt6) FROM doris.p6_dates.t WHERE id = 1").onlyValue
        val dorisWeek = DorisTestCluster.queryScalar("SELECT CAST(date_trunc(dt6, 'week') AS STRING) FROM p6_dates.t WHERE id = 1")
        // if these ever AGREE, week can be re-admitted to PROVEN_UNITS
        assertThat(dorisWeek).startsWith("0000-01-01")
        assertThat(trinoWeek.toString()).doesNotStartWith("0000-01-01")
    }

    // --- predicate position: comes free from the ENGINE, not from these rules ---

    @Test
    fun testPredicatePositionIsAlreadyCoveredByDomainUnwrapping() {
        // Trino unwraps CAST/date_trunc comparisons into range domains on the source column
        // (UnwrapCastInComparison / UnwrapDateTruncInComparison), so WHERE date(x) = ... is
        // already fully pushed as a datetime RANGE — no projection rule involved.
        for (predicate in listOf(
            "date(dt6) = DATE '2026-07-19'",
            "CAST(dt6 AS DATE) = DATE '2026-07-19'",
            "date_trunc('day', dt6) = TIMESTAMP '2026-07-19 00:00:00'",
        )) {
            val sql = "SELECT id FROM doris.p6_dates.t WHERE $predicate"
            assertThat(query(sql)).describedAs(predicate).isFullyPushedDown()
            assertThat(rows(session, sql)).describedAs(predicate).isEqualTo(listOf("[3]", "[8]"))
        }
        // the remote shape is a range on the COLUMN, not a projected expression
        assertThat(remoteSql("SELECT id FROM doris.p6_dates.t WHERE date(dt6) = DATE '2026-07-19'"))
            .contains("`dt6` >=").doesNotContain("CAST(`dt6` AS DATE)")
    }

    companion object {
        private fun provisionFixture() {
            DorisTestCluster.executeAsRoot(
                "DROP DATABASE IF EXISTS p6_dates",
                "CREATE DATABASE p6_dates",
                """
                CREATE TABLE p6_dates.t (
                    id INT NOT NULL, v BIGINT, u VARCHAR(20),
                    dt0 DATETIME, dt3 DATETIME(3), dt6 DATETIME(6)
                ) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                INSERT INTO p6_dates.t VALUES
                (1, 1, 'day', '0000-01-01 00:00:00', '0000-01-01 00:00:00', '0000-01-01 00:00:00'),
                (2, 2, 'day', '9999-12-31 23:59:59', '9999-12-31 23:59:59.999', '9999-12-31 23:59:59.999999'),
                (3, 3, 'day', '2026-07-19 14:30:45', '2026-07-19 14:30:45.123', '2026-07-19 14:30:45.123456'),
                (4, 4, 'day', '2026-07-20 00:00:00', '2026-07-20 00:00:00', '2026-07-20 00:00:00'),
                (5, 5, 'day', '2026-01-01 00:00:00', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
                (6, 6, 'day', '2026-03-31 23:59:59', '2026-03-31 23:59:59.999', '2026-03-31 23:59:59.999999'),
                (7, 7, 'day', '2026-04-01 00:00:00', '2026-04-01 00:00:00', '2026-04-01 00:00:00'),
                (8, 8, 'day', '2026-07-19 02:15:00', '2026-07-19 02:15:00.001', '2026-07-19 02:15:00.000001'),
                (9, NULL, NULL, NULL, NULL, NULL)
                """.trimIndent(),
            )
        }
    }
}
