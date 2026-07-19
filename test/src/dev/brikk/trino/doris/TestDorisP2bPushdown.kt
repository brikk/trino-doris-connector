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
import io.trino.sql.planner.plan.FilterNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * P2b typed ARRAY predicate pushdown: differential (pushdown vs no-pushdown vs Doris oracle),
 * NULL-semantics truth-table PINS for both engines (the exact live-proven tables the rules'
 * safety arguments rest on — a Trino or Doris upgrade that shifts any cell fails loud here),
 * plan-shape assertions, and audit-log proof of the generated remote SQL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisP2bPushdown : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    private fun noPushdownSession(): Session = Session.builder(session)
        .setSystemProperty("complex_expression_pushdown", "false")
        .build()

    // --- truth-table pins: the safety arguments live and die by these exact cells ---

    @Test
    fun testTrinoContainsTruthTablePin() {
        assertThat(computeActual("SELECT contains(ARRAY[1,2,3], 2)").onlyValue).isEqualTo(true)
        assertThat(computeActual("SELECT contains(ARRAY[1,2,3], 5)").onlyValue).isEqualTo(false)
        assertThat(computeActual("SELECT contains(ARRAY[1,NULL,3], 3)").onlyValue).isEqualTo(true)
        // THE divergent cell: not-found-with-NULL-elements is NULL in Trino, 0 in Doris —
        // equivalent only as a top-level WHERE conjunct (both drop the row).
        assertThat(computeActual("SELECT contains(ARRAY[1,NULL,3], 5)").onlyValue).isNull()
        assertThat(computeActual("SELECT contains(CAST(NULL AS array(integer)), 5)").onlyValue).isNull()
        assertThat(computeActual("SELECT contains(CAST(ARRAY[] AS array(integer)), 5)").onlyValue).isEqualTo(false)
        // NULL needles are never pushed (rule guard); Trino returns NULL for them.
        assertThat(computeActual("SELECT contains(ARRAY[1,2,3], NULL)").onlyValue).isNull()
    }

    @Test
    fun testDorisArrayContainsTruthTablePin() {
        val row = DorisTestCluster.querySingleColumn(
            """
            SELECT CONCAT_WS('|',
                CAST(array_contains(ARRAY(1,2,3), 2) AS STRING),
                CAST(array_contains(ARRAY(1,2,3), 5) AS STRING),
                CAST(array_contains(ARRAY(1,NULL,3), 3) AS STRING),
                CAST(array_contains(ARRAY(1,NULL,3), 5) AS STRING),
                COALESCE(CAST(array_contains(CAST(NULL AS ARRAY<INT>), 5) AS STRING), 'NULL'),
                CAST(array_contains(ARRAY(), 5) AS STRING))
            """.trimIndent(),
        ).single()
        // found=1, notfound=0, found_with_null=1, notfound_with_null=0 (vs Trino NULL — the
        // predicate-only divergence), null_array=NULL, empty=0
        assertThat(row).isEqualTo("1|0|1|0|NULL|0")
    }

    @Test
    fun testTrinoArraysOverlapTruthTablePin() {
        assertThat(computeActual("SELECT arrays_overlap(ARRAY[1,2], ARRAY[2,3])").onlyValue).isEqualTo(true)
        assertThat(computeActual("SELECT arrays_overlap(ARRAY[1,2], ARRAY[3,4])").onlyValue).isEqualTo(false)
        assertThat(computeActual("SELECT arrays_overlap(ARRAY[1,NULL], ARRAY[3,4])").onlyValue).isNull()
        // THE over-return hazard cell: Trino NULL; bare Doris arrays_overlap returns 1.
        assertThat(computeActual("SELECT arrays_overlap(ARRAY[1,NULL], ARRAY[NULL,4])").onlyValue).isNull()
        assertThat(computeActual("SELECT arrays_overlap(ARRAY[1,NULL], ARRAY[1,4])").onlyValue).isEqualTo(true)
        assertThat(computeActual("SELECT arrays_overlap(CAST(NULL AS array(integer)), ARRAY[1])").onlyValue).isNull()
        assertThat(computeActual("SELECT arrays_overlap(CAST(ARRAY[] AS array(integer)), ARRAY[1,2])").onlyValue).isEqualTo(false)
    }

    @Test
    fun testDorisArraysOverlapTruthTablePinIncludingTheGuardWrapper() {
        // Bare Doris arrays_overlap matches NULL elements to each other -> 1 (over-return vs
        // Trino NULL). The pushed rendering strips one side's NULLs, restoring equivalence.
        assertThat(
            DorisTestCluster.queryScalar("SELECT CAST(arrays_overlap(ARRAY(1,NULL), ARRAY(NULL,4)) AS STRING)"),
        ).isEqualTo("1")
        val fixed = DorisTestCluster.querySingleColumn(
            """
            SELECT CONCAT_WS('|',
                CAST(arrays_overlap(array_filter(x -> x IS NOT NULL, ARRAY(1,NULL)), ARRAY(NULL,4)) AS STRING),
                CAST(arrays_overlap(array_filter(x -> x IS NOT NULL, ARRAY(1,NULL)), ARRAY(1,4)) AS STRING),
                CAST(arrays_overlap(array_filter(x -> x IS NOT NULL, ARRAY(1,2)), ARRAY(2,3)) AS STRING),
                CAST(arrays_overlap(array_filter(x -> x IS NOT NULL, ARRAY(1,2)), ARRAY(3,4)) AS STRING),
                COALESCE(CAST(arrays_overlap(array_filter(x -> x IS NOT NULL, CAST(NULL AS ARRAY<INT>)), ARRAY(1)) AS STRING), 'NULL'),
                COALESCE(CAST(arrays_overlap(array_filter(x -> x IS NOT NULL, ARRAY(1,2)), CAST(NULL AS ARRAY<INT>)) AS STRING), 'NULL'),
                CAST(arrays_overlap(array_filter(x -> x IS NOT NULL, ARRAY()), ARRAY(1,2)) AS STRING))
            """.trimIndent(),
        ).single()
        // both_null_elems FIXED to 0; overlap kept; NULL arrays propagate; empty false.
        assertThat(fixed).isEqualTo("0|1|1|0|NULL|NULL|0")
    }

    @Test
    fun testArrayPositionTruthTablePinsMatchExactly() {
        // Trino: 1-based, 0-if-absent (even with NULL elements), NULL for NULL array.
        assertThat(computeActual("SELECT array_position(ARRAY[10,20,30], 20)").onlyValue).isEqualTo(2L)
        assertThat(computeActual("SELECT array_position(ARRAY[10,20,30], 5)").onlyValue).isEqualTo(0L)
        assertThat(computeActual("SELECT array_position(ARRAY[NULL,20], 20)").onlyValue).isEqualTo(2L)
        assertThat(computeActual("SELECT array_position(ARRAY[NULL,20], 5)").onlyValue).isEqualTo(0L)
        assertThat(computeActual("SELECT array_position(CAST(NULL AS array(integer)), 5)").onlyValue).isNull()
        // Doris: identical on every cell (value-level equivalence for non-NULL needles).
        val doris = DorisTestCluster.querySingleColumn(
            """
            SELECT CONCAT_WS('|',
                CAST(array_position(ARRAY(10,20,30), 20) AS STRING),
                CAST(array_position(ARRAY(10,20,30), 5) AS STRING),
                CAST(array_position(ARRAY(NULL,20), 20) AS STRING),
                CAST(array_position(ARRAY(NULL,20), 5) AS STRING),
                COALESCE(CAST(array_position(CAST(NULL AS ARRAY<INT>), 5) AS STRING), 'NULL'))
            """.trimIndent(),
        ).single()
        assertThat(doris).isEqualTo("2|0|2|0|NULL")
    }

    // --- differential: pushdown == no-pushdown == oracle, including the NULL-tricky rows ---

    @Test
    fun testContainsPushdownDifferential() {
        // Rows: 1=[1,2,3], 2=[4,5], 3=[1,NULL], 4=NULL, 5=[], 6=[7,NULL]
        assertPushedAndEquivalent("contains(a_int, 1)") // found incl. NULL-element row 3
        assertPushedAndEquivalent("contains(a_int, 5)") // row 3 not-found-with-NULL: both drop
        assertPushedAndEquivalent("contains(a_int, 7)")
        assertPushedAndEquivalent("contains(a_bigint, BIGINT '9223372036854775807')")
        assertPushedAndEquivalent("contains(a_dec, CAST('1.10' AS decimal(9,2)))")
        assertPushedAndEquivalent("contains(a_ldec, CAST('1.0000000000' AS decimal(38,10)))")
        assertPushedAndEquivalent("contains(a_li, DECIMAL '99999999999999999999999999999999999999')")
        assertPushedAndEquivalent("contains(a_date, DATE '0000-01-01')")
        assertPushedAndEquivalent("contains(a_dt6, TIMESTAMP '2021-06-15 12:34:56.789012')")
        assertPushedAndEquivalent("contains(a_bool, true)")
        // direct Doris oracle triangulation for the NULL-tricky needle
        assertThat(
            computeActual("SELECT id FROM doris.p2b_pushdown.t WHERE contains(a_int, 1) ORDER BY id").onlyColumn.toList(),
        ).isEqualTo(
            DorisTestCluster.querySingleColumn("SELECT id FROM p2b_pushdown.t WHERE array_contains(a_int, 1) ORDER BY id")
                .map { it!!.toInt() },
        )
    }

    @Test
    fun testArraysOverlapPushdownDifferential() {
        // Row 3 is the over-return trap: a_int=[1,NULL], b_int=[NULL,9] -> Trino NULL (drop);
        // an unguarded remote arrays_overlap would return it.
        assertPushedAndEquivalent("arrays_overlap(a_int, b_int)")
        assertThat(
            computeActual("SELECT id FROM doris.p2b_pushdown.t WHERE arrays_overlap(a_int, b_int) ORDER BY id").onlyColumn.toList(),
        ).isEqualTo(listOf(1, 6))
    }

    @Test
    fun testArrayPositionComparisonPushdownDifferential() {
        assertPushedAndEquivalent("array_position(a_int, 1) = 1")
        assertPushedAndEquivalent("array_position(a_int, 5) = 0") // absent -> 0 on both engines
        assertPushedAndEquivalent("array_position(a_int, 2) > 1")
        assertPushedAndEquivalent("array_position(a_int, 7) >= 1")
        assertPushedAndEquivalent("array_position(a_int, 1) <> 0")
        assertPushedAndEquivalent("1 <= array_position(a_int, 2)") // flipped orientation
    }

    // --- negatives: unsupported shapes stay local (and remain CORRECT) ---

    @Test
    fun testUnsupportedShapesStayLocalAndCorrect() {
        // approximate element family: excluded from pushdown (spike §7.5)
        assertLocalAndEquivalent("contains(a_double, DOUBLE '2.5')")
        // non-literal needle
        assertLocalAndEquivalent("contains(a_int, id)")
        // boolean composition is deliberately not pushed: contains is only
        // predicate-level equivalent, and these are exactly the contexts that would break it
        assertLocalAndEquivalent("NOT contains(a_int, 1)")
        assertLocalAndEquivalent("contains(a_int, 5) IS NULL")
        // type-coerced array argument (CAST wraps the variable) stays local
        assertLocalAndEquivalent("arrays_overlap(a_int, a_bigint)")
    }

    @Test
    fun testNotContainsSemanticsPreserved() {
        // The reason composition is not pushed: for rows with NULL elements and no match
        // (row 3 = [1,NULL], row 6 = [7,NULL]) Trino's NOT contains(a_int, 5) is NULL (row
        // dropped), while a remote NOT(array_contains(...)) is NOT(0)=1 (row RETURNED).
        assertThat(
            computeActual("SELECT id FROM doris.p2b_pushdown.t WHERE NOT contains(a_int, 5) ORDER BY id").onlyColumn.toList(),
        ).isEqualTo(listOf(1, 5))
        // ... and the Doris-side counterfactual proves the over-return hazard is real:
        assertThat(
            DorisTestCluster.querySingleColumn(
                "SELECT id FROM p2b_pushdown.t WHERE NOT array_contains(a_int, 5) ORDER BY id",
            ).map { it!!.toInt() },
        ).isEqualTo(listOf(1, 3, 5, 6)) // rows 3 and 6 over-returned by the remote form
    }

    // --- remote SQL shape via the FE audit log ---

    @Test
    fun testRemoteSqlShapes() {
        assertRemoteSqlContains(
            "SELECT id FROM doris.p2b_pushdown.t WHERE contains(a_int, 1)",
            "(array_contains(`a_int`, 1))",
        )
        assertRemoteSqlContains(
            "SELECT id FROM doris.p2b_pushdown.t WHERE arrays_overlap(a_int, b_int)",
            "(arrays_overlap(array_filter(x -> x IS NOT NULL, `a_int`), `b_int`))",
        )
        assertRemoteSqlContains(
            "SELECT id FROM doris.p2b_pushdown.t WHERE array_position(a_int, 1) = 1",
            "(array_position(`a_int`, 1) = 1)",
        )
        assertRemoteSqlContains(
            "SELECT id FROM doris.p2b_pushdown.t WHERE contains(a_date, DATE '2021-06-15')",
            "(array_contains(`a_date`, '2021-06-15'))",
        )
    }

    // --- helpers ---

    private fun assertPushedAndEquivalent(predicate: String) {
        val sql = "SELECT id FROM doris.p2b_pushdown.t WHERE $predicate"
        // isFullyPushedDown also verifies results against the pushdown-disabled session.
        assertThat(query(sql)).isFullyPushedDown()
        // explicit differential belt (ordered):
        val pushed = computeActual("$sql ORDER BY id").onlyColumn.toList()
        val local = computeActual(noPushdownSession(), "$sql ORDER BY id").onlyColumn.toList()
        assertThat(pushed).describedAs(predicate).isEqualTo(local)
    }

    private fun assertLocalAndEquivalent(predicate: String) {
        val sql = "SELECT id FROM doris.p2b_pushdown.t WHERE $predicate"
        assertThat(query(sql)).isNotFullyPushedDown(FilterNode::class.java)
        val withRules = computeActual("$sql ORDER BY id").onlyColumn.toList()
        val local = computeActual(noPushdownSession(), "$sql ORDER BY id").onlyColumn.toList()
        assertThat(withRules).describedAs(predicate).isEqualTo(local)
    }

    private fun assertRemoteSqlContains(sql: String, expectedFragment: String) {
        val execution = getDistributedQueryRunner().executeWithPlan(session, sql)
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        val statements = DorisTestCluster.awaitAuditLogStatements(marker, AUDIT_LOG_TIMEOUT_MILLIS)
        val scan = statements.single { it.contains("`t`") || it.contains("p2b_pushdown") }
        assertThat(scan).describedAs(sql).contains(expectedFragment)
    }

    companion object {
        private const val AUDIT_LOG_TIMEOUT_MILLIS = 60_000L

        private fun provisionFixture() {
            DorisTestCluster.executeAsRoot(
                "DROP DATABASE IF EXISTS p2b_pushdown",
                "CREATE DATABASE p2b_pushdown",
                """
                CREATE TABLE p2b_pushdown.t (
                    id INT NOT NULL,
                    a_int ARRAY<INT>, b_int ARRAY<INT>, a_bigint ARRAY<BIGINT>,
                    a_dec ARRAY<DECIMALV3(9, 2)>, a_ldec ARRAY<DECIMALV3(38, 10)>, a_li ARRAY<LARGEINT>,
                    a_date ARRAY<DATE>, a_dt6 ARRAY<DATETIME(6)>, a_bool ARRAY<BOOLEAN>,
                    a_double ARRAY<DOUBLE>
                ) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                INSERT INTO p2b_pushdown.t VALUES
                (1, ARRAY(1, 2, 3), ARRAY(3, 4), ARRAY(9223372036854775807),
                    ARRAY(1.10, 2.20), ARRAY(CAST('1.0000000000' AS DECIMALV3(38, 10))),
                    ARRAY(CAST('99999999999999999999999999999999999999' AS LARGEINT)),
                    ARRAY('2021-06-15', '0000-01-01'), ARRAY('2021-06-15 12:34:56.789012'),
                    ARRAY(true, false), ARRAY(2.5)),
                (2, ARRAY(4, 5), ARRAY(6, 7), ARRAY(-9223372036854775808),
                    ARRAY(9.99), ARRAY(CAST('0.0000000001' AS DECIMALV3(38, 10))),
                    ARRAY(CAST('-1' AS LARGEINT)),
                    ARRAY('9999-12-31'), ARRAY('0000-01-01 00:00:00.000000'),
                    ARRAY(false), ARRAY(3.5)),
                (3, ARRAY(1, NULL), ARRAY(NULL, 9), ARRAY(NULL, 1),
                    ARRAY(NULL, 1.10), ARRAY(NULL), ARRAY(NULL),
                    ARRAY(NULL, '2021-06-15'), ARRAY(NULL), ARRAY(NULL, true),
                    ARRAY(NULL, 2.5)),
                (4, NULL, ARRAY(1), NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
                (5, ARRAY(), ARRAY(), ARRAY(), ARRAY(), ARRAY(), ARRAY(), ARRAY(), ARRAY(), ARRAY(), ARRAY()),
                (6, ARRAY(7, NULL), ARRAY(7, NULL), ARRAY(7), ARRAY(7.00), ARRAY(CAST('7' AS DECIMALV3(38, 10))),
                    ARRAY(CAST('7' AS LARGEINT)), ARRAY('2021-06-15'), ARRAY('2021-06-15 12:34:56.789012'),
                    ARRAY(true), ARRAY(2.5))
                """.trimIndent(),
            )
        }
    }
}
