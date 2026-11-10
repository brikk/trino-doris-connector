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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisSelectionAndApproximateAggregates : DorisPushdownTest("b2_agg", { provisionFixture() }) {

    private val local: Session
        get() = fullyLocalSession(session)


    @Test
    fun testConditionalAggregationIsEngineDenied() {
        // Trino does not offer masked aggregates or CASE arguments to the connector.
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
            assertThat(rows(session, sql)).describedAs(sql).isEqualTo(rows(local, sql))
        }
    }


    @Test
    fun testAvgStaysWholeAndManualDecompositionPushes() {
        // Trino offers AVG as one aggregate; manual sum/count leaves division in Trino.
        val avg = "SELECT avg(dec18) FROM doris.b2_agg.t"
        assertThat(query(avg)).isNotFullyPushedDown(AggregationNode::class.java)
        assertThat(rows(session, avg)).isEqualTo(rows(local, avg))

        val decomposed = "SELECT sum(dec18) / count(dec18) FROM doris.b2_agg.t"
        assertThat(query(decomposed)).isNotFullyPushedDown(ProjectNode::class.java)
        assertThat(remoteSql(decomposed)).contains("sum(`dec18`)").contains("count(`dec18`)")
        assertThat(rows(session, decomposed)).isEqualTo(rows(fullyLocalSession(session), decomposed))
        assertThat(computeActual(decomposed).onlyValue).isEqualTo(computeActual(fullyLocalSession(session), avg).onlyValue)
    }


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
        assertThat(rows(session, sql)).isEqualTo(rows(local, sql))
    }

    @Test
    fun testAnyValueTypeAndNullSoundness() {
        val sql = "SELECT g, any_value(a_nn) FROM doris.b2_agg.t GROUP BY g"
        assertThat(query(sql)).isFullyPushedDown()
        // nondeterministic by contract on BOTH engines: assert membership, not equality
        for (row in computeActual(sql).materializedRows) {
            val g = row.getField(0)
            val value = row.getField(1)
            val groupValues = computeActual(local, "SELECT a_nn FROM doris.b2_agg.t WHERE g = $g").onlyColumn
            assertThat(groupValues).describedAs("group $g").contains(value)
        }
        assertThat(remoteSql("SELECT any_value(a_nn) FROM doris.b2_agg.t")).contains("any_value(`a_nn`)")
        // NULL soundness on a NULLABLE argument: {NULL, 7} answers NON-NULL on both engines
        // (probed); all-NULL answers NULL on both
        assertThat(query("SELECT any_value(a_n) FROM doris.b2_agg.t WHERE g = 5")).isFullyPushedDown()
        assertThat(computeActual("SELECT any_value(a_n) FROM doris.b2_agg.t WHERE g = 5").onlyValue).isEqualTo(7)
        assertThat(computeActual("SELECT any_value(a_n) FROM doris.b2_agg.t WHERE g = 4").onlyValue).isNull()
    }


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
            provisionFixture(
                "b2_agg",
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
