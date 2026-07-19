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
package dev.brikk.doris.trino.plugin

import io.trino.Session
import io.trino.sql.planner.plan.FilterNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * P3 boolean composition over the VALUE-SAFE tier: NOT/AND/OR compose `array_position`
 * comparisons (value-identical operands, P2b proof) and NOTHING else — `contains` /
 * `arrays_overlap` are predicate-level only and must never compose (their counterfactual
 * over-return is proven in TestDorisP2bPushdown). The 3VL pins here are the second half of
 * the composition-safety proof: identical operand values + identical connective truth tables
 * => identical composite values.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisP3Composition : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    private fun noPushdownSession(): Session = Session.builder(session)
        .setSystemProperty("complex_expression_pushdown", "false")
        .build()

    // --- 3VL pins (both engines) ---

    @Test
    fun testTrinoThreeValuedLogicPin() {
        assertThat(computeActual("SELECT NOT CAST(NULL AS boolean)").onlyValue).isNull()
        assertThat(computeActual("SELECT true AND CAST(NULL AS boolean)").onlyValue).isNull()
        assertThat(computeActual("SELECT false AND CAST(NULL AS boolean)").onlyValue).isEqualTo(false)
        assertThat(computeActual("SELECT true OR CAST(NULL AS boolean)").onlyValue).isEqualTo(true)
        assertThat(computeActual("SELECT false OR CAST(NULL AS boolean)").onlyValue).isNull()
        assertThat(computeActual("SELECT CAST(NULL AS boolean) OR CAST(NULL AS boolean)").onlyValue).isNull()
    }

    @Test
    fun testDorisThreeValuedLogicPin() {
        val cells = DorisTestCluster.querySingleColumn(
            """
            SELECT CONCAT_WS('|',
                COALESCE(CAST(NOT CAST(NULL AS BOOLEAN) AS STRING), 'N'),
                COALESCE(CAST((TRUE AND CAST(NULL AS BOOLEAN)) AS STRING), 'N'),
                COALESCE(CAST((FALSE AND CAST(NULL AS BOOLEAN)) AS STRING), 'N'),
                COALESCE(CAST((TRUE OR CAST(NULL AS BOOLEAN)) AS STRING), 'N'),
                COALESCE(CAST((FALSE OR CAST(NULL AS BOOLEAN)) AS STRING), 'N'),
                COALESCE(CAST((CAST(NULL AS BOOLEAN) OR CAST(NULL AS BOOLEAN)) AS STRING), 'N'))
            """.trimIndent(),
        ).single()
        // NOT N=N; T AND N=N; F AND N=F; T OR N=T; F OR N=N; N OR N=N — identical to Trino.
        assertThat(cells).isEqualTo("N|N|0|1|N|N")
    }

    // --- pushed compositions (differential incl. NULL-array rows) ---

    @Test
    fun testOrOfArrayPositionComparisonsPushes() {
        assertPushedAndEquivalent("array_position(a_int, 1) = 1 OR array_position(a_int, 9) = 1")
        assertPushedAndEquivalent("array_position(a_int, 5) = 0 OR array_position(b_int, 9) = 2")
    }

    @Test
    fun testNestedAndOrCompositionPushes() {
        assertPushedAndEquivalent(
            "(array_position(a_int, 1) = 1 AND array_position(b_int, 9) = 2) OR array_position(a_int, 7) >= 1",
        )
    }

    @Test
    fun testNotOfArrayPositionComparisonPushes() {
        // (the engine may canonicalize NOT(=) into <>; either way the shape pushes)
        assertPushedAndEquivalent("NOT (array_position(a_int, 5) = 1)")
        assertPushedAndEquivalent("NOT (array_position(a_int, 1) = 1 OR array_position(a_int, 9) = 1)")
    }

    @Test
    fun testRemoteCompositionSqlShape() {
        val execution = getDistributedQueryRunner().executeWithPlan(
            session,
            "SELECT id FROM doris.p3_composition.t WHERE array_position(a_int, 1) = 1 OR array_position(a_int, 9) = 1",
        )
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        val statements = DorisTestCluster.awaitAuditLogStatements(marker, 60_000)
        val scan = statements.single { it.contains("p3_composition") }
        assertThat(scan).contains("((array_position(`a_int`, 1) = 1) OR (array_position(`a_int`, 9) = 1))")
    }

    // --- predicate-level rules must NOT compose ---

    @Test
    fun testContainsAndOverlapNeverCompose() {
        // NOT / OR / AND over contains or arrays_overlap: the whole conjunct stays local
        // (and remains correct) — pushing it would over-return on NULL-element rows.
        assertLocalAndEquivalent("NOT contains(a_int, 5)")
        assertLocalAndEquivalent("contains(a_int, 1) OR array_position(a_int, 9) = 1")
        assertLocalAndEquivalent("arrays_overlap(a_int, b_int) OR array_position(a_int, 9) = 1")
        assertLocalAndEquivalent("NOT (contains(a_int, 5) AND array_position(a_int, 1) = 1)")
        // ... while plain contains still pushes on its own (predicate-level, top-level conjunct)
        assertThat(query("SELECT id FROM doris.p3_composition.t WHERE contains(a_int, 1)")).isFullyPushedDown()
    }

    // --- helpers ---

    private fun assertPushedAndEquivalent(predicate: String) {
        val sql = "SELECT id FROM doris.p3_composition.t WHERE $predicate"
        assertThat(query(sql)).isFullyPushedDown()
        val pushed = computeActual("$sql ORDER BY id").onlyColumn.toList()
        val local = computeActual(noPushdownSession(), "$sql ORDER BY id").onlyColumn.toList()
        assertThat(pushed).describedAs(predicate).isEqualTo(local)
    }

    private fun assertLocalAndEquivalent(predicate: String) {
        val sql = "SELECT id FROM doris.p3_composition.t WHERE $predicate"
        assertThat(query(sql)).isNotFullyPushedDown(FilterNode::class.java)
        val withRules = computeActual("$sql ORDER BY id").onlyColumn.toList()
        val local = computeActual(noPushdownSession(), "$sql ORDER BY id").onlyColumn.toList()
        assertThat(withRules).describedAs(predicate).isEqualTo(local)
    }

    companion object {
        private fun provisionFixture() {
            DorisTestCluster.executeAsRoot(
                "DROP DATABASE IF EXISTS p3_composition",
                "CREATE DATABASE p3_composition",
                """
                CREATE TABLE p3_composition.t (id INT NOT NULL, a_int ARRAY<INT>, b_int ARRAY<INT>)
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                // NULL arrays and NULL elements are the 3VL-critical rows
                """
                INSERT INTO p3_composition.t VALUES
                (1, ARRAY(1, 2, 3), ARRAY(3, 9)),
                (2, ARRAY(4, 5), ARRAY(6, 7)),
                (3, ARRAY(1, NULL), ARRAY(NULL, 9)),
                (4, NULL, ARRAY(1)),
                (5, ARRAY(), ARRAY()),
                (6, ARRAY(7, NULL), ARRAY(9, 9)),
                (7, ARRAY(9), NULL)
                """.trimIndent(),
            )
        }
    }
}
