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
import io.trino.sql.planner.plan.TopNNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Safe TopN (PLAN §6.5, P3): all four Trino NULL orderings rendered as Doris-native
 * `NULLS FIRST/LAST` (live-probed supported on 4.1.3), non-text exact sort keys only,
 * always WITH the LIMIT. Differentials run against `topn_pushdown_enabled=false`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisP3TopN : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    private fun noTopNSession(): Session = Session.builder(session)
        .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "topn_pushdown_enabled", "false")
        .build()

    @Test
    fun testAllFourNullOrderingsPushAndMatch() {
        for (ordering in listOf("ASC NULLS FIRST", "ASC NULLS LAST", "DESC NULLS FIRST", "DESC NULLS LAST")) {
            // selecting only the sort key keeps tied rows deterministic
            val sql = "SELECT c_int FROM doris.p3_topn.t ORDER BY c_int $ordering LIMIT 4"
            assertThat(query(sql)).ordered().isFullyPushedDown()
            val pushed = computeActual(sql).onlyColumn.toList()
            val local = computeActual(noTopNSession(), sql).onlyColumn.toList()
            assertThat(pushed).describedAs(ordering).isEqualTo(local)
        }
        // spot-check the NULL placement values themselves
        assertThat(computeActual("SELECT c_int FROM doris.p3_topn.t ORDER BY c_int ASC NULLS FIRST LIMIT 3").onlyColumn.toList())
            .isEqualTo(listOf(null, null, 1))
        assertThat(computeActual("SELECT c_int FROM doris.p3_topn.t ORDER BY c_int ASC NULLS LAST LIMIT 3").onlyColumn.toList())
            .isEqualTo(listOf(1, 3, 5))
        assertThat(computeActual("SELECT c_int FROM doris.p3_topn.t ORDER BY c_int DESC NULLS FIRST LIMIT 3").onlyColumn.toList())
            .isEqualTo(listOf(null, null, 9))
        assertThat(computeActual("SELECT c_int FROM doris.p3_topn.t ORDER BY c_int DESC NULLS LAST LIMIT 3").onlyColumn.toList())
            .isEqualTo(listOf(9, 7, 5))
    }

    @Test
    fun testAllPushableSortKeyFamilies() {
        for (column in listOf("c_dec", "c_li", "c_date", "c_dt6", "c_bool")) {
            val sql = "SELECT $column FROM doris.p3_topn.t ORDER BY $column ASC NULLS LAST LIMIT 4"
            assertThat(query(sql)).ordered().isFullyPushedDown()
            assertThat(computeActual(sql).onlyColumn.toList())
                .describedAs(column)
                .isEqualTo(computeActual(noTopNSession(), sql).onlyColumn.toList())
        }
    }

    @Test
    fun testMultiKeyTopNWithUniqueTieBreak() {
        val sql = "SELECT id, c_int FROM doris.p3_topn.t ORDER BY c_int ASC NULLS LAST, id DESC LIMIT 4"
        assertThat(query(sql)).ordered().isFullyPushedDown()
        assertThat(computeActual(sql).materializedRows.map { it.getField(0) })
            .isEqualTo(computeActual(noTopNSession(), sql).materializedRows.map { it.getField(0) })
    }

    @Test
    fun testTextAndApproximateSortKeysStayLocal() {
        // CHAR/VARCHAR: G5 collation unproven; DOUBLE/REAL: NaN placement unproven — the
        // TopN must remain in Trino, and results must still be correct.
        for (column in listOf("c_vc", "c_double")) {
            val sql = "SELECT id FROM doris.p3_topn.t ORDER BY $column ASC NULLS LAST, id LIMIT 3"
            assertThat(query(sql)).ordered().isNotFullyPushedDown(TopNNode::class.java)
            assertThat(computeActual(sql).onlyColumn.toList())
                .describedAs(column)
                .isEqualTo(computeActual(noTopNSession(), sql).onlyColumn.toList())
        }
        // a mixed key list with one unsupported key keeps the whole TopN local
        assertThat(query("SELECT id FROM doris.p3_topn.t ORDER BY c_int, c_vc LIMIT 3"))
            .ordered().isNotFullyPushedDown(TopNNode::class.java)
    }

    @Test
    fun testLargeLimitBeyond65535IsExact() {
        // PLAN §9.2 safety: Doris `default_order_by_limit=-1` (probed — no bare-ORDER-BY
        // truncation, which we never emit anyway), and a pushed TopN with limit > 65535
        // returns exactly that many rows in order.
        val sql = "SELECT n FROM doris.p0_probe.nums ORDER BY n ASC NULLS LAST LIMIT 70000"
        assertThat(query(sql)).ordered().skipResultsCorrectnessCheckForPushdown().isFullyPushedDown()
        val rows = computeActual(sql).onlyColumn.toList()
        assertThat(rows).hasSize(70000)
        assertThat(rows.first()).isEqualTo(0L)
        assertThat(rows.last()).isEqualTo(69999L)
    }

    @Test
    fun testRemoteTopNSqlShape() {
        val execution = getDistributedQueryRunner()
            .executeWithPlan(session, "SELECT c_int FROM doris.p3_topn.t ORDER BY c_int ASC NULLS LAST LIMIT 4")
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        val statements = DorisTestCluster.awaitAuditLogStatements(marker, 60_000)
        val scan = statements.single { it.contains("p3_topn") }
        assertThat(scan).contains("ORDER BY `c_int` ASC NULLS LAST LIMIT 4")
    }

    companion object {
        private fun provisionFixture() {
            DorisTestCluster.executeAsRoot(
                "DROP DATABASE IF EXISTS p3_topn",
                "CREATE DATABASE p3_topn",
                """
                CREATE TABLE p3_topn.t (
                    id INT NOT NULL,
                    c_int INT, c_dec DECIMALV3(9, 2), c_li LARGEINT,
                    c_date DATE, c_dt6 DATETIME(6), c_bool BOOLEAN,
                    c_vc VARCHAR(20), c_double DOUBLE
                ) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                INSERT INTO p3_topn.t VALUES
                (1, 5, 1.10, CAST('99999999999999999999999999999999999999' AS LARGEINT),
                    '2021-06-15', '2021-06-15 12:34:56.789012', true, 'banana', 2.5),
                (2, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
                (3, 1, -9.99, CAST('-99999999999999999999999999999999999999' AS LARGEINT),
                    '0000-01-01', '0000-01-01 00:00:00.000000', false, 'apple', -1.5),
                (4, 9, 0.01, CAST('0' AS LARGEINT), '9999-12-31', '9999-12-31 23:59:59.999999', true, 'cherry', 9.75),
                (5, NULL, 2.20, CAST('7' AS LARGEINT), '2000-02-29', '2021-06-15 12:34:56.000001', false, 'apple', 0.0),
                (6, 3, NULL, NULL, NULL, NULL, NULL, 'date', NULL),
                (7, 7, 1.10, CAST('7' AS LARGEINT), '2021-06-15', '2021-06-15 12:34:56.789012', true, NULL, 2.5),
                (8, 5, NULL, NULL, '2021-06-15', NULL, NULL, 'apple', NULL)
                """.trimIndent(),
            )
        }
    }
}
