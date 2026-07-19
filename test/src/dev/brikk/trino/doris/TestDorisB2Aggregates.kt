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
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal

/**
 * READ-ONLY-MAX batch 2: aggregate pushdown expansion. Engine-path findings and Doris truth
 * tables in `NOTES-readonly-max-batch2.md`. Differentials: pushed vs
 * `aggregation_pushdown_enabled=false` vs the direct Doris oracle.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisB2Aggregates : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    private val noAggPushdown: Session
        get() = Session.builder(session)
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "aggregation_pushdown_enabled", "false")
            .build()

    private fun rows(session: Session, sql: String): List<String> =
        computeActual(session, sql).materializedRows.map { it.toString() }.sorted()

    private fun assertPushedAndIdentical(sql: String) {
        assertThat(query(sql)).describedAs(sql).isFullyPushedDown()
        assertThat(rows(session, sql)).describedAs(sql).isEqualTo(rows(noAggPushdown, sql))
    }

    private fun remoteSql(session: Session, sql: String): String {
        val execution = getDistributedQueryRunner().executeWithPlan(session, sql)
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        return DorisTestCluster.awaitAuditLogStatements(marker, 60_000).single { it.contains("b2_agg") }
    }

    private fun remoteSql(sql: String): String = remoteSql(session, sql)

    // --- item 1: conditional aggregation — ENGINE-DENIED at 483, pinned precisely ---

    @Test
    fun testConditionalAggregationIsEngineDenied() {
        // FILTER (and count_if, which desugars to it) becomes an aggregation MASK during
        // planning (ImplementFilteredAggregations); PushAggregationIntoTableScan bails when
        // any aggregation carries a mask (483 source, line 110: `allMatch(aggregation ->
        // aggregation.getMask().isEmpty())`) — so applyAggregation is NEVER invoked for
        // these shapes (verified via rejection-log silence). sum(CASE WHEN ...) hits the
        // batch-1 wall instead: CASE has no ConnectorExpression form, so the aggregate's
        // ARGUMENT is untranslatable. Both walls are the ENGINE's, pinned here so a future
        // Trino that lifts either shows up as a plan change.
        for (sql in listOf(
            "SELECT count_if(a_nn > 0) FROM doris.b2_agg.t",
            "SELECT count(*) FILTER (WHERE a_nn > 0) FROM doris.b2_agg.t",
            "SELECT sum(dec18) FILTER (WHERE a_nn > 0) FROM doris.b2_agg.t",
            "SELECT sum(CASE WHEN a_nn > 0 THEN dec18 END) FROM doris.b2_agg.t",
        )) {
            // masked aggregations plan as PARTIAL+FINAL over exchanges (node patterns cannot
            // match through them) — the decisive assertion is the WIRE: the remote statement
            // must carry NO aggregate at all (plain column scan; aggregation entirely local)
            assertThat(remoteSql(sql)).describedAs(sql)
                .doesNotContain("count(").doesNotContain("sum(")
            assertThat(rows(session, sql)).describedAs(sql).isEqualTo(rows(noAggPushdown, sql))
        }
    }

    // --- item 2: avg — deny sharpened; the manual decomposition is the pushable path ---

    @Test
    fun testAvgStaysWholeAndManualDecompositionPushes() {
        // The engine offers avg WHOLE to the connector (probed: implementAggregation
        // received avg(decimal) and rejected it) and has NO sum+count decomposition rule at
        // 483; the SPI maps one AggregateFunction to ONE remote expression, so a
        // connector-side split is structurally impossible. The deny therefore stands —
        // Doris avg truncates decimal scale and double-accumulates differently (P4 pins).
        val avg = "SELECT avg(dec18) FROM doris.b2_agg.t"
        assertThat(query(avg)).isNotFullyPushedDown(AggregationNode::class.java)
        assertThat(rows(session, avg)).isEqualTo(rows(noAggPushdown, avg))

        // ...but the MANUAL decomposition pushes fully and is EXACT: sum(DECIMAL(18,2)) and
        // count both push; Trino performs the division. The case that made raw avg diverge
        // (scale-truncating decimal division) is exact on this path.
        val decomposed = "SELECT sum(dec18), count(dec18) FROM doris.b2_agg.t"
        assertPushedAndIdentical(decomposed)
        assertThat(remoteSql(decomposed)).contains("sum(`dec18`)").contains("count(`dec18`)")
        val row = computeActual(decomposed).materializedRows.single()
        val exact = (row.getField(0) as BigDecimal).divide(BigDecimal(row.getField(1) as Long), 2, java.math.RoundingMode.HALF_UP)
        assertThat(exact).isEqualTo(computeActual(avg).onlyValue) // division in Trino == Trino's avg
    }

    // --- item 3: min_by / max_by / any_value ---

    @Test
    fun testMinByMaxByPushForNonNullableValues() {
        for (value in listOf("a_nn", "v_nn", "dc_nn", "dt_nn")) {
            val sql = "SELECT g, min_by($value, b), max_by($value, b) FROM doris.b2_agg.t GROUP BY g"
            assertPushedAndIdentical(sql)
        }
        assertThat(remoteSql("SELECT min_by(v_nn, b) FROM doris.b2_agg.t"))
            .contains("min_by(`v_nn`, `b`)")
        // NULL-KEY rows are ignored on both engines (group 2 has one); an all-NULL-key
        // group answers NULL on both (group 4) — covered by the differentials above; spot-pin:
        assertThat(computeActual("SELECT min_by(v_nn, b) FROM doris.b2_agg.t WHERE g = 4").onlyValue).isNull()
    }

    @Test
    fun testMinByNullableValueStaysLocalWithTheDivergencePinned() {
        // THE guard's reason, live on both engines: for {(a=NULL,b=1), (a=60,b=2)} Trino
        // min_by keeps the NULL payload of the minimal key; Doris SKIPS the NULL-value row.
        assertThat(computeActual("SELECT min_by(a_n, b) FROM doris.b2_agg.t WHERE g = 3").onlyValue)
            .isNull() // Trino (local evaluation): payload of minimal key is NULL
        assertThat(DorisTestCluster.queryScalar("SELECT min_by(a_n, b) FROM b2_agg.t WHERE g = 3"))
            .isEqualTo("60") // Doris: NULL-value row skipped
        // -> NULLABLE value columns stay local (and correct)
        val sql = "SELECT g, min_by(a_n, b) FROM doris.b2_agg.t GROUP BY g"
        assertThat(query(sql)).isNotFullyPushedDown(AggregationNode::class.java)
        assertThat(rows(session, sql)).isEqualTo(rows(noAggPushdown, sql))
    }

    @Test
    fun testAnyValueTypeAndNullSoundness() {
        val sql = "SELECT g, any_value(a_nn) FROM doris.b2_agg.t GROUP BY g"
        assertThat(query(sql)).isFullyPushedDown()
        // nondeterministic by contract on BOTH engines: assert membership, not equality
        for (row in computeActual(sql).materializedRows) {
            val g = row.getField(0)
            val value = row.getField(1)
            val groupValues = computeActual(noAggPushdown, "SELECT a_nn FROM doris.b2_agg.t WHERE g = $g").onlyColumn
            assertThat(groupValues).describedAs("group $g").contains(value)
        }
        assertThat(remoteSql("SELECT any_value(a_nn) FROM doris.b2_agg.t")).contains("any_value(`a_nn`)")
        // NULL soundness on a NULLABLE argument: {NULL, 7} answers NON-NULL on both engines
        // (probed); all-NULL answers NULL on both
        assertThat(query("SELECT any_value(a_n) FROM doris.b2_agg.t WHERE g = 5")).isFullyPushedDown()
        assertThat(computeActual("SELECT any_value(a_n) FROM doris.b2_agg.t WHERE g = 5").onlyValue).isEqualTo(7)
        assertThat(computeActual("SELECT any_value(a_n) FROM doris.b2_agg.t WHERE g = 4").onlyValue).isNull()
    }

    // --- item 4: approx_distinct — opt-in only ---

    @Test
    fun testApproxDistinctIsOptIn() {
        val sql = "SELECT approx_distinct(a_nn) FROM doris.b2_agg.t"
        // default OFF: estimates from different sketches are legitimately different numbers
        assertThat(query(sql)).isNotFullyPushedDown(AggregationNode::class.java)
        // opted in: pushes as approx_count_distinct; assert type soundness only
        val optIn = Session.builder(session)
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "approximate_pushdown", "true")
            .build()
        assertThat(query(optIn, sql)).isFullyPushedDown()
        assertThat(remoteSql(optIn, sql)).contains("approx_count_distinct(`a_nn`)")
        assertThat(computeActual(optIn, sql).onlyValue as Long).isGreaterThan(0L)
        assertThat(computeActual(optIn, "SELECT approx_distinct(a_nn) FROM doris.b2_agg.t WHERE g = 99").onlyValue)
            .isEqualTo(0L) // empty input -> 0 on both engines (probed)
    }

    // --- composition with batch-1 projections + pushed WHERE ---

    @Test
    fun testCompositionWithProjectedGroupingAndPushedWhere() {
        val sql = "SELECT year(dt_nn) AS y, min_by(v_nn, b), any_value(a_nn), count(*) " +
            "FROM doris.b2_agg.t WHERE a_nn > 0 GROUP BY 1"
        assertThat(query(sql)).isFullyPushedDown()
        val statement = remoteSql(sql)
        assertThat(statement).contains("year(`dt_nn`)").contains("min_by(")
            .contains("any_value(").contains("`a_nn` > 0").contains("GROUP BY")
    }

    companion object {
        private fun provisionFixture() {
            DorisTestCluster.executeAsRoot(
                "DROP DATABASE IF EXISTS b2_agg",
                "CREATE DATABASE b2_agg",
                """
                CREATE TABLE b2_agg.t (
                    g INT NOT NULL, a_nn INT NOT NULL, a_n INT, b INT,
                    v_nn VARCHAR(20) NOT NULL, dc_nn DECIMAL(10,2) NOT NULL,
                    dt_nn DATETIME(6) NOT NULL, dec18 DECIMAL(18,2)
                ) DUPLICATE KEY(g) DISTRIBUTED BY HASH(g) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                // tie-free keys per group (min_by/max_by determinism); NULL-key and
                // NULL-value adversaries in dedicated groups
                """
                INSERT INTO b2_agg.t VALUES
                (1, 10, 10, 3, 'ten',    1.10, '2026-01-01 00:00:00.000001', 10.01),
                (1, 20, 20, 1, 'twenty', 2.20, '2026-02-02 00:00:00',        20.02),
                (1, 30, 30, 2, 'thirty', 3.30, '2026-03-03 00:00:00',        30.03),
                (2, 40, 40, NULL, 'forty', 4.40, '2027-04-04 00:00:00',      40.04),
                (2, 50, 50, 5, 'fifty',  5.50, '2027-05-05 00:00:00',        NULL),
                (3, 60, NULL, 1, 'sixty', 6.60, '2025-06-06 00:00:00',       60.06),
                (3, 61, 60, 2, 'sixtyone', 6.61, '2025-06-07 00:00:00',      0.01),
                (4, 70, NULL, NULL, 'seventy', 7.70, '2024-07-07 00:00:00',  70.07),
                (5, 80, NULL, 1, 'eighty', 8.80, '2026-08-08 00:00:00',      80.08),
                (5, 81, 7, 2, 'eightyone', 8.81, '2026-08-09 00:00:00',      81.09)
                """.trimIndent(),
            )
        }
    }
}
