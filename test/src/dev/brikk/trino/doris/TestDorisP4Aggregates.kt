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
import io.trino.sql.planner.plan.AggregationNode
import io.trino.sql.planner.plan.ProjectNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal

/**
 * Verified aggregate pushdown (PLAN §6.5, P4): per-family differentials (pushdown on vs
 * `aggregation_pushdown_enabled=false` vs direct-Doris oracle), plan-shape assertions
 * (isFullyPushedDown for enabled families, retained [AggregationNode] for denied ones),
 * audit-log remote-SQL shapes, and the fail-loud overflow/avg divergence pins that justify
 * every exclusion. Doris-side gating facts are pinned in [TestDorisP4AggregateProbes];
 * narrative in `dev-docs/NOTES-p4-aggregates.md`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisP4Aggregates : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    private fun noAggSession(): Session = Session.builder(session)
        .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "aggregation_pushdown_enabled", "false")
        .build()

    /** Differential: pushdown-enabled results must be row-identical to the local (disabled) plan. */
    private fun assertSameAsLocal(sql: String, description: String = sql) {
        val pushed = computeActual(sql).materializedRows.sortedBy { it.toString() }
        val local = computeActual(noAggSession(), sql).materializedRows.sortedBy { it.toString() }
        assertThat(pushed).describedAs(description).isEqualTo(local)
    }

    @Test
    fun testCountFamiliesPushedAndMatch() {
        // count(*) and count(col) push for EVERY column type — counting non-NULLs has no
        // value-semantics hazard (text/array columns included).
        for (expression in listOf("count(*)", "count(c_int)", "count(c_big)", "count(c_li)", "count(c_vc)", "count(c_arr)", "count(c_bool)")) {
            val sql = "SELECT $expression FROM doris.p4_agg.t"
            assertThat(query(sql)).isFullyPushedDown()
            assertSameAsLocal(sql, expression)
        }
        // direct-Doris oracle for the base counts
        assertThat(computeActual("SELECT count(*) FROM doris.p4_agg.t").onlyValue)
            .isEqualTo(DorisTestCluster.queryScalar("SELECT count(*) FROM p4_agg.t")!!.toLong())
        assertThat(computeActual("SELECT count(c_vc) FROM doris.p4_agg.t").onlyValue)
            .isEqualTo(DorisTestCluster.queryScalar("SELECT count(c_vc) FROM p4_agg.t")!!.toLong())
    }

    @Test
    fun testCountDistinctExactTypesPushTextStaysLocal() {
        val pushedSql = "SELECT count(DISTINCT c_int) FROM doris.p4_agg.t"
        assertThat(query(pushedSql)).isFullyPushedDown()
        assertSameAsLocal(pushedSql)
        assertThat(computeActual(pushedSql).onlyValue)
            .isEqualTo(DorisTestCluster.queryScalar("SELECT count(DISTINCT c_int) FROM p4_agg.t")!!.toLong())

        // text distinctness is collation/case-hazardous -> local, still correct
        val localSql = "SELECT count(DISTINCT c_vc) FROM doris.p4_agg.t"
        assertThat(query(localSql)).isNotFullyPushedDown(AggregationNode::class.java)
        assertSameAsLocal(localSql)
        // 'apple'/'Apple'/'banana'/'BANANA'/'straße' are 5 distinct values under Trino semantics
        assertThat(computeActual(localSql).onlyValue).isEqualTo(5L)
    }

    @Test
    fun testMinMaxPushedFamilies() {
        // every exact-pushable family, incl. LARGEINT extremes, DATE/DATETIME(6) extremes
        // (0000-01-01 / 9999-12-31 23:59:59.999999), DECIMAL scale edges, and BOOLEAN
        for (column in listOf("c_tiny", "c_small", "c_int", "c_big", "c_li", "c_dec", "c_dec18", "c_dec38", "c_date", "c_dt6", "c_bool")) {
            val sql = "SELECT min($column), max($column) FROM doris.p4_agg.t"
            assertThat(query(sql)).isFullyPushedDown()
            assertSameAsLocal(sql, column)
        }
        // spot-check boundary values arrive exactly
        val bigRow = computeActual("SELECT min(c_big), max(c_big) FROM doris.p4_agg.t").materializedRows.single()
        assertThat(bigRow.getField(0)).isEqualTo(-9223372036854775708L)
        assertThat(bigRow.getField(1)).isEqualTo(9223372036854775707L)
        val largeintRow = computeActual("SELECT min(c_li), max(c_li) FROM doris.p4_agg.t").materializedRows.single()
        assertThat(largeintRow.getField(0)).isEqualTo(BigDecimal("-99999999999999999999999999999999999999"))
        assertThat(largeintRow.getField(1)).isEqualTo(BigDecimal("99999999999999999999999999999999999999"))
    }

    @Test
    fun testMinMaxTextAndApproximateFamiliesStayLocal() {
        // CHAR/VARCHAR: collation; REAL/DOUBLE: Doris max = NaN where Trino max = Infinity
        // (pinDoubleMaxReturnsNanWhereTrinoReturnsInfinity) — retained AggregationNode, results
        // stay Trino-exact.
        for (column in listOf("c_vc", "c_real", "c_double")) {
            val sql = "SELECT min($column), max($column) FROM doris.p4_agg.t"
            assertThat(query(sql)).isNotFullyPushedDown(AggregationNode::class.java)
            assertSameAsLocal(sql, column)
        }
        val textRow = computeActual("SELECT min(c_vc), max(c_vc) FROM doris.p4_agg.t").materializedRows.single()
        assertThat(textRow.getField(0)).isEqualTo("Apple") // Trino codepoint order, NOT Doris collation
        assertThat(textRow.getField(1)).isEqualTo("straße")
    }

    @Test
    fun testSumDecimalPushedAndScaleExact() {
        for (expression in listOf("sum(c_dec)", "sum(c_dec18)", "sum(DISTINCT c_dec)")) {
            val sql = "SELECT $expression FROM doris.p4_agg.t"
            assertThat(query(sql)).isFullyPushedDown()
            assertSameAsLocal(sql, expression)
        }
        // exact values and scale round-trip (Trino output decimal(38, s) == Doris decimalv3(38, s))
        assertThat(computeActual("SELECT sum(c_dec) FROM doris.p4_agg.t").onlyValue).isEqualTo(BigDecimal("-6.68"))
        assertThat(computeActual("SELECT sum(c_dec18) FROM doris.p4_agg.t").onlyValue).isEqualTo(BigDecimal("12345678901234.5679"))
        assertThat(DorisTestCluster.queryScalar("SELECT sum(c_dec18) FROM p4_agg.t")).isEqualTo("12345678901234.5679")
    }

    @Test
    fun testSumDeniedFamiliesStayLocal() {
        // integer sums (Doris wraps at 2^64 where Trino throws), LARGEINT/DECIMAL(p>18)
        // (Doris wraps at 2^128), REAL/DOUBLE (approximate): all retain the AggregationNode
        for (column in listOf("c_big", "c_li", "c_dec38", "c_real", "c_double")) {
            val sql = "SELECT sum($column) FROM doris.p4_agg.t"
            assertThat(query(sql)).isNotFullyPushedDown(AggregationNode::class.java)
            assertSameAsLocal(sql, column)
        }
        // narrow ints widen through a local CAST projection (sum(tinyint) -> bigint)
        for (column in listOf("c_tiny", "c_small", "c_int")) {
            val sql = "SELECT sum($column) FROM doris.p4_agg.t"
            assertThat(query(sql)).isNotFullyPushedDown(AggregationNode::class.java, ProjectNode::class.java)
            assertSameAsLocal(sql, column)
        }
        assertThat(computeActual("SELECT sum(c_big) FROM doris.p4_agg.t").onlyValue).isEqualTo(-21L)
    }

    @Test
    fun testSumOverflowFamiliesStayLocalAndFailLoud() {
        // Trino semantics preserved BECAUSE these stay local: fail loud on overflow...
        assertQueryFails("SELECT sum(c_big) FROM doris.p4_agg.t_overflow", ".*overflow.*")
        assertQueryFails("SELECT sum(c_li) FROM doris.p4_agg.t_overflow", ".*[Oo]verflow.*")
        // ...where a pushed plan would have silently returned Doris's wrapped values:
        assertThat(DorisTestCluster.queryScalar("SELECT sum(c_big) FROM p4_agg.t_overflow")).isEqualTo("-9223372036854775808")
        assertThat(DorisTestCluster.queryScalar("SELECT sum(c_li) FROM p4_agg.t_overflow")).isEqualTo("0")
    }

    @Test
    fun testAvgBigintAccumulationDivergenceStaysLocal() {
        // Trino 483 avg(bigint) accumulates in DOUBLE (BigintAverageAggregations: order-
        // dependent, precision-lossy — over {2^53, 1, -2^53, 1} a 1 added while 2^53 is in
        // the accumulator is LOST -> 0.25); Doris avg(BIGINT) computes an exact wide integer
        // sum -> always 0.5. Divergent semantics -> avg(bigint) must stay local so results
        // remain TRINO-exact.
        val sql = "SELECT avg(v) FROM doris.p4_agg.t_avgb"
        assertThat(query(sql)).isNotFullyPushedDown(AggregationNode::class.java)
        assertThat(computeActual(sql).onlyValue as Double)
            .describedAs("Trino double-accumulated avg (order-dependent)")
            .isIn(0.25, 0.5)
        assertThat(DorisTestCluster.queryScalar("SELECT avg(v) FROM p4_agg.t_avgb")).isEqualTo("0.5")
    }

    @Test
    fun testAvgStaysLocalWithTrinoSemantics() {
        for (expression in listOf("avg(c_dec)", "avg(c_dec18)", "avg(c_big)", "avg(c_double)")) {
            val sql = "SELECT $expression FROM doris.p4_agg.t"
            assertThat(query(sql)).isNotFullyPushedDown(AggregationNode::class.java)
            assertSameAsLocal(sql, expression)
        }
        // avg(int) widens through a local CAST projection
        assertThat(query("SELECT avg(c_int) FROM doris.p4_agg.t"))
            .isNotFullyPushedDown(AggregationNode::class.java, ProjectNode::class.java)
        assertSameAsLocal("SELECT avg(c_int) FROM doris.p4_agg.t")
        // the live divergence that keeps avg local: Trino HALF_UP vs Doris truncation at s=4
        val avgSql = "SELECT avg(v) FROM doris.p4_agg.t_avg"
        assertThat(query(avgSql)).isNotFullyPushedDown(AggregationNode::class.java)
        assertThat(computeActual(avgSql).onlyValue).isEqualTo(BigDecimal("0.0001"))
        assertThat(DorisTestCluster.queryScalar("SELECT CAST(avg(v) AS DECIMALV3(9,4)) FROM p4_agg.t_avg")).isEqualTo("0.0000")
    }

    @Test
    fun testGroupByPushed() {
        // NULL keys form one group; g_int=2 is an all-NULL-aggregates group (sum NULL, count 0)
        val sql = "SELECT g_int, count(*), count(c_int), sum(c_dec), min(c_date), max(c_dt6) FROM doris.p4_agg.t GROUP BY g_int"
        assertThat(query(sql)).isFullyPushedDown()
        assertSameAsLocal(sql)

        val multiKey = "SELECT g_int, c_bool, count(*), sum(c_dec18) FROM doris.p4_agg.t GROUP BY g_int, c_bool"
        assertThat(query(multiKey)).isFullyPushedDown()
        assertSameAsLocal(multiKey)

        // direct-Doris oracle over the NULL-key and all-NULL groups (NULLS FIRST on the
        // Trino side to align with Doris's NULLS-first ASC default)
        assertThat(DorisTestCluster.querySingleColumn("SELECT count(*) FROM p4_agg.t GROUP BY g_int ORDER BY g_int"))
            .isEqualTo(
                computeActual("SELECT count(*) FROM doris.p4_agg.t GROUP BY g_int ORDER BY g_int NULLS FIRST")
                    .onlyColumn.toList().map { it.toString() },
            )
    }

    @Test
    fun testGroupByTextOrDoubleKeysStayLocal() {
        // unicode text keys (ü/U/ß/ss/straße) group under TRINO semantics — retained
        // AggregationNode, values preserved exactly
        val textKey = "SELECT g_vc, count(*) FROM doris.p4_agg.t GROUP BY g_vc"
        assertThat(query(textKey)).isNotFullyPushedDown(AggregationNode::class.java)
        assertSameAsLocal(textKey)
        assertThat(computeActual(textKey).materializedRows.map { it.getField(0) })
            .containsExactlyInAnyOrder("ü", "U", "ß", "ss", "straße", null)

        val doubleKey = "SELECT g_double, count(*) FROM doris.p4_agg.t GROUP BY g_double"
        assertThat(query(doubleKey)).isNotFullyPushedDown(AggregationNode::class.java)
        assertSameAsLocal(doubleKey)

        // one hazardous key poisons the whole grouping set even if other keys are exact
        assertThat(query("SELECT g_int, g_vc, count(*) FROM doris.p4_agg.t GROUP BY g_int, g_vc"))
            .isNotFullyPushedDown(AggregationNode::class.java)
    }

    @Test
    fun testEmptyTableAggregates() {
        val globalSql = "SELECT count(*), count(v), sum(v), min(v), max(d) FROM doris.p4_agg.empty_t"
        assertThat(query(globalSql)).isFullyPushedDown()
        assertSameAsLocal(globalSql)
        val row = computeActual(globalSql).materializedRows.single()
        assertThat(row.getField(0)).isEqualTo(0L)
        assertThat(row.getField(1)).isEqualTo(0L)
        assertThat(row.getField(2)).isNull()
        assertThat(row.getField(3)).isNull()
        assertThat(row.getField(4)).isNull()

        val groupedSql = "SELECT v, count(*) FROM doris.p4_agg.empty_t GROUP BY v"
        assertThat(query(groupedSql)).isFullyPushedDown()
        assertThat(computeActual(groupedSql).rowCount).isEqualTo(0)
    }

    @Test
    fun testAggregateComposesWithPushedPredicates() {
        // aggregate over a pushed domain + pushed contains() — the WHOLE query goes remote
        val sql = "SELECT count(*) FROM doris.p4_agg.t WHERE c_int >= 1 AND contains(c_arr, 7)"
        assertThat(query(sql)).isFullyPushedDown()
        assertThat(computeActual(sql).onlyValue).isEqualTo(2L) // rows 1 and 5
        assertSameAsLocal(sql)

        val execution = getDistributedQueryRunner().executeWithPlan(session, sql)
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        val statement = DorisTestCluster.awaitAuditLogStatements(marker, 60_000).single { it.contains("p4_agg") }
        assertThat(statement).contains("count(*)")
        assertThat(statement).contains("array_contains(`c_arr`, 7)")
        assertThat(statement).contains("`c_int` >= 1")
    }

    @Test
    fun testRemoteAggregateSqlShapes() {
        val execution = getDistributedQueryRunner().executeWithPlan(
            session,
            "SELECT g_int, count(*), sum(c_dec), min(c_int), max(c_dt6) FROM doris.p4_agg.t GROUP BY g_int",
        )
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        val statement = DorisTestCluster.awaitAuditLogStatements(marker, 60_000).single { it.contains("p4_agg") }
        assertThat(statement).contains("count(*)")
        assertThat(statement).contains("sum(`c_dec`)")
        assertThat(statement).contains("min(`c_int`)")
        assertThat(statement).contains("max(`c_dt6`)")
        assertThat(statement).contains("GROUP BY `g_int`")
    }

    @Test
    fun testSessionDisableKeepsAggregationLocal() {
        val sql = "SELECT g_int, count(*), sum(c_dec) FROM doris.p4_agg.t GROUP BY g_int"
        assertThat(query(noAggSession(), sql)).isNotFullyPushedDown(AggregationNode::class.java)
    }

    companion object {
        private fun provisionFixture() {
            provisionMainTable()
            provisionEdgeCaseTables()
        }

        private fun provisionMainTable() {
            DorisTestCluster.executeAsRoot(
                "CREATE DATABASE IF NOT EXISTS p4_agg",
                "DROP TABLE IF EXISTS p4_agg.t",
                """
                CREATE TABLE p4_agg.t (
                    id INT NOT NULL,
                    c_tiny TINYINT, c_small SMALLINT, c_int INT, c_big BIGINT,
                    c_li LARGEINT, c_dec DECIMALV3(9,2), c_dec18 DECIMALV3(18,4), c_dec38 DECIMALV3(38,2),
                    c_date DATE, c_dt6 DATETIME(6), c_bool BOOLEAN,
                    c_vc VARCHAR(20), c_real FLOAT, c_double DOUBLE, c_arr ARRAY<INT>,
                    g_int INT, g_vc VARCHAR(20), g_double DOUBLE
                ) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                // c_big values are chosen so NO partial sum can overflow locally in any
                // accumulation order (positives total MAX-70, negatives total MIN+50) while
                // still exercising near-±2^63 boundary reads via min/max.
                """
                INSERT INTO p4_agg.t VALUES
                (1, 1, 1, 1, 9223372036854775707, CAST('99999999999999999999999999999999999999' AS LARGEINT),
                    1.10, 12345678901234.5678, 1000000000000000000000000000000000.25,
                    '2021-06-15', '2021-06-15 12:34:56.789012', true, 'apple', 1.5, 2.5, [1, 7, 3], 1, 'ü', 1.0),
                (2, -2, -2, -2, -9223372036854775708, CAST('-99999999999999999999999999999999999999' AS LARGEINT),
                    -9.99, -99999999999999.9999, -1000000000000000000000000000000000.25,
                    '0000-01-01', '0000-01-01 00:00:00.000000', false, 'Apple', -1.5, -2.5, [4, 5], 1, 'U', 1.0),
                (3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 2, 'ß', NULL),
                (4, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 2, 'ss', NULL),
                (5, 3, 3, 3, 30, CAST('7' AS LARGEINT), 2.20, 99999999999999.9999, 0.00,
                    '9999-12-31', '9999-12-31 23:59:59.999999', true, 'banana', 0.0, 0.0, [7], NULL, NULL, NULL),
                (6, 4, 4, 4, -50, CAST('0' AS LARGEINT), 0.01, 0.0001, 5.75,
                    '2000-02-29', '2021-06-15 12:34:56.000001', false, 'BANANA', 2.5, 3.5, [], NULL, 'ü', 2.0),
                (7, 5, 5, 5, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'straße', NULL, NULL, NULL, 3, 'straße', 2.0)
                """.trimIndent(),
            )
        }

        private fun provisionEdgeCaseTables() {
            DorisTestCluster.executeAsRoot(
                "DROP TABLE IF EXISTS p4_agg.empty_t",
                "DROP TABLE IF EXISTS p4_agg.t_overflow",
                "DROP TABLE IF EXISTS p4_agg.t_avg",
                "DROP TABLE IF EXISTS p4_agg.t_avgb",
                """
                CREATE TABLE p4_agg.empty_t (id INT, v DECIMALV3(9,2), d DATE)
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                CREATE TABLE p4_agg.t_overflow (id INT, c_big BIGINT, c_li LARGEINT)
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                // c_big: MAX + 1 -> Trino sum/avg throw, Doris wraps; c_li: 4 x 2^126 = 2^128
                // -> Trino decimal sum throws, Doris wraps to 0
                """
                INSERT INTO p4_agg.t_overflow VALUES
                (1, 9223372036854775807, CAST('85070591730234615865843651857942052864' AS LARGEINT)),
                (2, 1, CAST('85070591730234615865843651857942052864' AS LARGEINT)),
                (3, NULL, CAST('85070591730234615865843651857942052864' AS LARGEINT)),
                (4, NULL, CAST('85070591730234615865843651857942052864' AS LARGEINT))
                """.trimIndent(),
                """
                CREATE TABLE p4_agg.t_avg (id INT, v DECIMALV3(9,4))
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                "INSERT INTO p4_agg.t_avg VALUES (1, 0.0001), (2, 0.0000)",
                """
                CREATE TABLE p4_agg.t_avgb (id INT, v BIGINT)
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                // {2^53, 1, -2^53, 1}: exact mean 0.5; a double accumulator that holds 2^53
                // when a 1 arrives silently loses it (Trino), an exact integer sum does not (Doris)
                "INSERT INTO p4_agg.t_avgb VALUES (1, 9007199254740992), (2, 1), (3, -9007199254740992), (4, 1)",
            )
        }
    }
}
