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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * P5 cost-aware join pushdown (PLAN §6.5): OFF by default; INNER/LEFT/RIGHT on the exact
 * non-text key set; `IS NOT DISTINCT FROM` -> `<=>` (truth table live-proven); FULL OUTER
 * and text/approximate keys excluded. Differentials run pushed (EAGER) vs local (default
 * OFF) over NULL keys, duplicate keys, and empty sides — the local plan is the ground
 * truth. Probe verdicts: `NOTES-p5-joins.md`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisP5JoinPushdown : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    private fun joinSession(strategy: String): Session = Session.builder(session)
        .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "join_pushdown_enabled", "true")
        .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "join_pushdown_strategy", strategy)
        .build()

    private val eager: Session get() = joinSession("EAGER")
    private val automatic: Session get() = joinSession("AUTOMATIC")

    /** Rows sorted client-side so plan shape (no ORDER BY) stays pushdown-assertable. */
    private fun rows(session: Session, sql: String): List<String> =
        computeActual(session, sql).materializedRows.map { it.toString() }.sorted()

    private fun assertPushedAndIdentical(sql: String) {
        assertThat(query(eager, sql)).describedAs(sql).isFullyPushedDown()
        assertThat(rows(eager, sql)).describedAs(sql).isEqualTo(rows(session, sql))
    }

    private fun remoteStatements(session: Session, sql: String): List<String> {
        val execution = getDistributedQueryRunner().executeWithPlan(session, sql)
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        return DorisTestCluster.awaitAuditLogStatements(marker, 60_000).filter { it.contains("p5_join") }
    }

    // --- gating ---

    @Test
    fun testJoinPushdownIsOffByDefault() {
        val sql = "SELECT l.lv, r.rv FROM doris.p5_join.l l JOIN doris.p5_join.r r ON l.k = r.k"
        assertThat(query(sql)).joinIsNotFullyPushedDown()
        // and enabling is purely per-session
        assertThat(query(eager, sql)).isFullyPushedDown()
    }

    // --- pushed shapes: differentials over the adversarial keys ---

    @Test
    fun testInnerLeftRightEqualityDifferentials() {
        for (joinKind in listOf("JOIN", "LEFT JOIN", "RIGHT JOIN")) {
            val sql = "SELECT l.lv, r.rv FROM doris.p5_join.l l $joinKind doris.p5_join.r r ON l.k = r.k"
            assertPushedAndIdentical(sql)
        }
        // exact semantics pins: NULL keys never match; duplicates multiply (2x2)
        val inner = rows(eager, "SELECT l.lv, r.rv FROM doris.p5_join.l l JOIN doris.p5_join.r r ON l.k = r.k")
        assertThat(inner).hasSize(5) // 1 + 2*2, no NULL matches
        assertThat(inner.none { it.contains("lnull") || it.contains("rnull") }).isTrue()
        val left = rows(eager, "SELECT l.lv, r.rv FROM doris.p5_join.l l LEFT JOIN doris.p5_join.r r ON l.k = r.k")
        assertThat(left).hasSize(7) // + NULL-extended l5 and lnull
        val right = rows(eager, "SELECT l.lv, r.rv FROM doris.p5_join.l l RIGHT JOIN doris.p5_join.r r ON l.k = r.k")
        assertThat(right).hasSize(7) // + NULL-extended r7 and rnull
    }

    @Test
    fun testIsNotDistinctFromPushesViaNullSafeEquals() {
        // Doris rejects IS NOT DISTINCT FROM syntax; the QueryBuilder renders <=> —
        // truth-table proven identical (NULL<=>NULL=1, NULL<=>1=0). LEFT JOIN shape:
        // for INNER joins the engine plans IS NOT DISTINCT FROM as cross-join + filter,
        // which never reaches applyJoin (pinned by the engine, not a connector choice).
        val sql = "SELECT l.lv, r.rv FROM doris.p5_join.l l LEFT JOIN doris.p5_join.r r ON l.k IS NOT DISTINCT FROM r.k"
        assertPushedAndIdentical(sql)
        // the NULL keys DO match each other under this operator
        assertThat(rows(eager, sql)).anySatisfy { assertThat(it).contains("lnull").contains("rnull") }
        assertThat(remoteStatements(eager, sql).single()).contains("<=>")
    }

    @Test
    fun testNonEquiConditionsPush() {
        // range/inequality operators render natively; NULL keys drop on both engines
        // (probed); LEFT JOIN keeps the condition in the join (INNER non-equi is planned
        // as cross+filter by the engine and never reaches applyJoin)
        for (op in listOf("<", "<=", ">", ">=", "<>")) {
            val sql = "SELECT l.lv, r.rv FROM doris.p5_join.l l LEFT JOIN doris.p5_join.r r ON l.k $op r.k"
            assertPushedAndIdentical(sql)
        }
    }

    @Test
    fun testEveryEligibleKeyTypePushes() {
        for (key in listOf("kb", "kd", "kli", "kdate", "kdt", "kbool")) {
            val sql = "SELECT l.lv, r.rv FROM doris.p5_join.l l JOIN doris.p5_join.r r ON l.$key = r.$key"
            assertPushedAndIdentical(sql)
        }
    }

    @Test
    fun testEmptySideDifferentials() {
        for (joinKind in listOf("JOIN", "LEFT JOIN", "RIGHT JOIN")) {
            assertPushedAndIdentical(
                "SELECT l.lv, e.ev FROM doris.p5_join.l l $joinKind doris.p5_join.e e ON l.k = e.k",
            )
        }
    }

    // --- excluded shapes stay local (and correct) ---

    @Test
    fun testExcludedShapesStayLocal() {
        val denied = listOf(
            // FULL OUTER: excluded join type
            "SELECT l.lv, r.rv FROM doris.p5_join.l l FULL JOIN doris.p5_join.r r ON l.k = r.k",
            // text keys: excluded regardless of string pushdown mode
            "SELECT l.lv, r.rv FROM doris.p5_join.l l JOIN doris.p5_join.r r ON l.kv = r.kv",
            // approximate keys: wire text of extreme doubles reads "Infinity" — remote and
            // local comparisons could see different values
            "SELECT l.lv, r.rv FROM doris.p5_join.l l JOIN doris.p5_join.r r ON l.kdbl = r.kdbl",
        )
        for (sql in denied) {
            assertThat(query(eager, sql)).describedAs(sql).joinIsNotFullyPushedDown()
            assertThat(rows(eager, sql)).describedAs(sql).isEqualTo(rows(session, sql))
        }
        // FULL string mode does NOT unlock text join keys (documented future, not v1)
        val fullMode = Session.builder(eager)
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "string_pushdown_mode", "FULL")
            .build()
        assertThat(query(fullMode, denied[1])).joinIsNotFullyPushedDown()
    }

    // --- snapshot posture + composition ---

    @Test
    fun testPushedJoinIsOneRemoteStatement() {
        // Snapshot consistency: a pushed join executes as ONE Doris statement, i.e. one
        // MVCC snapshot — strictly better than two independent scans stitched locally
        // (the connector's normal posture). PLAN §6.5's "test snapshot consistency".
        val sql = "SELECT l.lv, r.rv FROM doris.p5_join.l l JOIN doris.p5_join.r r ON l.k = r.k"
        val statements = remoteStatements(eager, sql)
        assertThat(statements).hasSize(1)
        assertThat(statements.single()).contains("INNER JOIN").contains("`l`").contains("`r`")
    }

    @Test
    fun testCompositionThroughPushedJoin() {
        // aggregate over a pushed join: still one remote statement
        val agg = "SELECT count(*) FROM doris.p5_join.l l JOIN doris.p5_join.r r ON l.k = r.k"
        assertThat(query(eager, agg)).isFullyPushedDown()
        assertThat(computeActual(eager, agg).onlyValue).isEqualTo(computeActual(agg).onlyValue)
        val aggStatement = remoteStatements(eager, agg).single()
        assertThat(aggStatement).contains("count(*)").contains("INNER JOIN")
        // predicates push INSIDE the join sides
        val filtered = "SELECT l.lv, r.rv FROM doris.p5_join.l l JOIN doris.p5_join.r r ON l.k = r.k WHERE l.id > 1"
        assertThat(query(eager, filtered)).isFullyPushedDown()
        assertThat(rows(eager, filtered)).isEqualTo(rows(session, filtered))
        assertThat(remoteStatements(eager, filtered).single()).contains("`id` > 1")
    }

    // --- AUTOMATIC strategy: the statistics-fed cost gate ---

    @Test
    fun testAutomaticPushesAnalyzedTablesAndKeepsStatlessLocal() {
        // both sides ANALYZE'd in setup -> sizes known and tiny -> AUTOMATIC pushes
        val analyzed = "SELECT l.lv, r.rv FROM doris.p5_join.l l JOIN doris.p5_join.r r ON l.k = r.k"
        assertThat(query(automatic, analyzed)).isFullyPushedDown()
        // stats dropped -> engine cannot size the join -> AUTOMATIC keeps it LOCAL...
        val statless = "SELECT a.v, b.v FROM doris.p5_join.bare_l a JOIN doris.p5_join.bare_r b ON a.k = b.k"
        assertThat(query(automatic, statless)).joinIsNotFullyPushedDown()
        // ...while EAGER pushes the identical shape — proving the cost gate (fed by
        // DorisTableStatisticsReader through getTableStatistics) is what decided
        assertThat(query(eager, statless)).isFullyPushedDown()
        assertThat(rows(automatic, statless)).isEqualTo(rows(eager, statless))
    }

    companion object {
        private fun provisionFixture() {
            DorisTestCluster.executeAsRoot(
                "DROP DATABASE IF EXISTS p5_join",
                "CREATE DATABASE p5_join",
                *listOf("l" to "lv", "r" to "rv").map { (table, label) ->
                    """
                    CREATE TABLE p5_join.$table (
                        id INT NOT NULL, k INT, kb BIGINT, kd DECIMAL(9,2), kli LARGEINT,
                        kdate DATE, kdt DATETIME(6), kbool BOOLEAN, kv VARCHAR(20), kdbl DOUBLE,
                        $label VARCHAR(20)
                    ) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                    """.trimIndent()
                }.toTypedArray(),
                """
                INSERT INTO p5_join.l VALUES
                (1, 1, 10, 1.25, 12345678901234567890123456789012345, '2021-01-01', '2021-01-01 00:00:00.000001', true,  'a', 1.5, 'l1'),
                (2, 2, 20, 2.25, 2, '2021-02-02', '2021-02-02 00:00:00', false, 'b', 2.5, 'l2'),
                (3, 2, 20, 2.25, 2, '2021-02-02', '2021-02-02 00:00:00', false, 'b', 2.5, 'l2b'),
                (4, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'lnull'),
                (5, 5, 50, 5.25, 5, '2021-05-05', '2021-05-05 00:00:00', true, 'e', 5.5, 'l5')
                """.trimIndent(),
                """
                INSERT INTO p5_join.r VALUES
                (1, 1, 10, 1.25, 12345678901234567890123456789012345, '2021-01-01', '2021-01-01 00:00:00.000001', true,  'a', 1.5, 'r1'),
                (2, 2, 20, 2.25, 2, '2021-02-02', '2021-02-02 00:00:00', false, 'b', 2.5, 'r2'),
                (3, 2, 20, 2.25, 2, '2021-02-02', '2021-02-02 00:00:00', false, 'b', 2.5, 'r2b'),
                (4, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'rnull'),
                (7, 7, 70, 7.25, 7, '2021-07-07', '2021-07-07 00:00:00', true, 'g', 7.5, 'r7')
                """.trimIndent(),
                "CREATE TABLE p5_join.e (k INT, ev VARCHAR(20)) " +
                    "DUPLICATE KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES (\"replication_num\" = \"1\")",
                "CREATE TABLE p5_join.bare_l (k INT, v VARCHAR(20)) " +
                    "DUPLICATE KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES (\"replication_num\" = \"1\")",
                "CREATE TABLE p5_join.bare_r (k INT, v VARCHAR(20)) " +
                    "DUPLICATE KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES (\"replication_num\" = \"1\")",
                "INSERT INTO p5_join.bare_l SELECT number, concat('a', number) FROM numbers('number' = '500')",
                "INSERT INTO p5_join.bare_r SELECT number, concat('b', number) FROM numbers('number' = '500')",
                // deterministic statistics for the AUTOMATIC test (via ROOT, never the connector)
                "ANALYZE TABLE p5_join.l WITH SYNC",
                "ANALYZE TABLE p5_join.r WITH SYNC",
                "ANALYZE TABLE p5_join.e WITH SYNC",
                "DROP STATS p5_join.bare_l",
                "DROP STATS p5_join.bare_r",
            )
        }
    }
}
