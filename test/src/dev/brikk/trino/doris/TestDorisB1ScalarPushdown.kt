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
import io.trino.sql.planner.plan.ProjectNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Batch-1 scalar expression pushdown: coalesce/nullif, temporal extraction, lower/upper
 * (mode-gated), length->char_length, starts_with->escaped-LIKE-prefix — plus the pinned
 * ENGINE DENIALS (CASE/IF never reach the connector at 483). Differentials compare pushed
 * vs `complex_expression_pushdown=false` (projections/predicate expressions local) vs the
 * direct Doris oracle. Probe verdicts: `NOTES-readonly-max-batch1.md`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisB1ScalarPushdown : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    private val local: Session
        get() = Session.builder(session)
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "complex_expression_pushdown", "false")
            .build()

    private fun modeSession(mode: String): Session = Session.builder(session)
        .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "string_pushdown_mode", mode)
        .build()

    private fun rows(session: Session, sql: String): List<String> =
        computeActual(session, sql).materializedRows.map { it.toString() }.sorted()

    private fun assertPushedAndIdentical(sql: String, session: Session = getSession()) {
        assertThat(query(session, sql)).describedAs(sql).isFullyPushedDown()
        assertThat(rows(session, sql)).describedAs(sql).isEqualTo(rows(local, sql))
    }

    private fun remoteSql(session: Session, sql: String): String {
        val execution = getDistributedQueryRunner().executeWithPlan(session, sql)
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        return DorisTestCluster.awaitAuditLogStatements(marker, 60_000).single { it.contains("b1_scalar") }
    }

    private fun remoteSql(sql: String): String = remoteSql(session, sql)

    // --- item 1: CASE / IF — ENGINE-DENIED, pinned ---

    @Test
    fun testCaseAndIfAreEngineDeniedButCorrect() {
        // Trino 483's ConnectorExpression grammar has NO conditional form: searched CASE
        // decomposes into fragments, simple predicate CASEs fold into comparisons upstream,
        // and IF desugars to CASE. The connector can never receive them (probed via the
        // rejection log), so these stay local BY ENGINE CONTRACT — pinned here so a future
        // Trino that starts translating conditionals shows up as a plan change.
        for (projection in listOf(
            "CASE WHEN n > 0 THEN 1 ELSE 2 END",
            "CASE n WHEN 5 THEN 'five' ELSE 'other' END",
            "IF(n > 0, 1, 2)",
        )) {
            val sql = "SELECT id, $projection FROM doris.b1_scalar.t"
            assertThat(query(sql)).describedAs(projection).isNotFullyPushedDown(ProjectNode::class.java)
            assertThat(rows(session, sql)).describedAs(projection).isEqualTo(rows(local, sql))
        }
    }

    // --- item 2: coalesce / nullif ---

    @Test
    fun testCoalesceAndNullifProjectionsPush() {
        for (projection in listOf(
            "coalesce(n, 0)",
            "coalesce(n, m, 0)",
            "nullif(n, 5)",
            "coalesce(nullif(n, 5), -1)", // composition
            "coalesce(v, 'absent')", // varchar passthrough
        )) {
            assertPushedAndIdentical("SELECT id, $projection FROM doris.b1_scalar.t")
        }
        assertThat(remoteSql("SELECT coalesce(n, 0) FROM doris.b1_scalar.t")).contains("coalesce(`n`, ")
        assertThat(remoteSql("SELECT coalesce(nullif(n, 5), -1) FROM doris.b1_scalar.t"))
            .contains("coalesce(nullif(`n`, ")
    }

    @Test
    fun testCoalesceNullifPredicatesAndGroupingPush() {
        for (predicate in listOf(
            "coalesce(n, 0) = 5",
            "coalesce(n, 0) > 3",
            "nullif(n, 5) IS NULL", // the equal-operands=NULL edge, as a predicate
            "coalesce(nullif(n, 5), -1) <> -1",
        )) {
            assertPushedAndIdentical("SELECT id FROM doris.b1_scalar.t WHERE $predicate")
        }
        assertThat(remoteSql("SELECT id FROM doris.b1_scalar.t WHERE nullif(n, 5) IS NULL"))
            .contains("(nullif(`n`, ").contains("IS NULL)")
        // grouping key composition (exact-type keys)
        assertPushedAndIdentical("SELECT coalesce(n, 0) AS g, count(*) FROM doris.b1_scalar.t GROUP BY 1")
        assertThat(remoteSql("SELECT coalesce(n, 0) AS g, count(*) FROM doris.b1_scalar.t GROUP BY 1"))
            .contains("coalesce(`n`, ").contains("GROUP BY")
    }

    @Test
    fun testNullifSemanticsPins() {
        // nullif(a,a)=NULL; NULL propagation; text nullif is byte-exact case-SENSITIVE
        assertThat(computeActual("SELECT nullif(n, 5) FROM doris.b1_scalar.t WHERE id = 1").onlyValue).isNull()
        assertThat(computeActual("SELECT coalesce(n, 0) FROM doris.b1_scalar.t WHERE id = 2").onlyValue).isEqualTo(0)
        assertThat(DorisTestCluster.queryScalar("SELECT nullif('a', 'A')")).isEqualTo("a")
        assertThat(computeActual("SELECT nullif('a', 'A')").onlyValue).isEqualTo("a")
    }

    // --- item 3: temporal extraction projections ---

    @Test
    fun testTemporalExtractionProjectionsPush() {
        for (function in listOf("year", "month", "day", "hour", "minute", "second")) {
            assertPushedAndIdentical("SELECT id, $function(dt6) FROM doris.b1_scalar.t")
            assertPushedAndIdentical("SELECT $function(dt0) AS g, count(*) FROM doris.b1_scalar.t GROUP BY 1")
        }
        assertThat(remoteSql("SELECT year(dt6) AS g, count(*) FROM doris.b1_scalar.t GROUP BY 1"))
            .contains("year(`dt6`)").contains("GROUP BY")
        // edges: 0000-01-01 / 9999-12-31 rows are IN the fixture and covered by the
        // differentials above; spot-pin the extreme values end-to-end
        assertThat(computeActual("SELECT year(dt6) FROM doris.b1_scalar.t WHERE id = 3").onlyValue).isEqualTo(0L)
        assertThat(computeActual("SELECT year(dt6) FROM doris.b1_scalar.t WHERE id = 4").onlyValue).isEqualTo(9999L)
        // predicate position comes free via upstream unwrapping (year(dt)=2026 -> dt range)
        assertThat(query("SELECT id FROM doris.b1_scalar.t WHERE year(dt6) = 2026")).isFullyPushedDown()
        assertThat(remoteSql("SELECT id FROM doris.b1_scalar.t WHERE year(dt6) = 2026"))
            .doesNotContain("year(").contains("`dt6` >=")
    }

    // --- item 4: lower / upper (identity battery-proven; pushed in all value modes) ---

    @Test
    fun testCaseFoldPushesWithModeContractRespected() {
        for (shape in listOf(
            "SELECT id, lower(v) FROM doris.b1_scalar.t",
            "SELECT id, upper(v) FROM doris.b1_scalar.t",
            "SELECT id FROM doris.b1_scalar.t WHERE lower(v) = 'abc'",
        )) {
            assertPushedAndIdentical(shape) // default GUARDED
        }
        assertThat(remoteSql("SELECT id FROM doris.b1_scalar.t WHERE lower(v) = 'abc'"))
            .contains("(lower(`v`) = ")
        // NULL_ONLY: no string VALUE comparison pushes (mode contract) — projection of the
        // transform alone is a value read and still pushes
        val nullOnly = modeSession("NULL_ONLY")
        assertThat(query(nullOnly, "SELECT id FROM doris.b1_scalar.t WHERE lower(v) = 'abc'"))
            .isNotFullyPushedDown(FilterNode::class.java)
        assertThat(rows(nullOnly, "SELECT id FROM doris.b1_scalar.t WHERE lower(v) = 'abc'"))
            .isEqualTo(rows(session, "SELECT id FROM doris.b1_scalar.t WHERE lower(v) = 'abc'"))
    }

    @Test
    fun testCaseFoldAgreementBatteryPins() {
        // The divergence hunt came back EMPTY: both engines implement the same
        // simple+special mapping. Pinned per adversary (Trino via the engine, Doris via
        // the oracle) so any future engine change on either side fails loud here.
        val battery = mapOf(
            "upper(U&'\\00B5')" to "\u039C", // micro -> GREEK CAPITAL MU (special mapping, BOTH)
            "upper(U&'\\00DF')" to "ß", // no SS expansion on either engine
            "lower(U&'\\0391\\03A3')" to "ασ", // no final-sigma contextual mapping on either
            "lower(U&'\\212A')" to "k", // Kelvin sign
            "upper(U&'\\017F')" to "S", // long s
            "lower(U&'\\0130')" to "i", // dotted capital I
        )
        for ((expr, expected) in battery) {
            assertThat(computeActual("SELECT $expr").onlyValue).describedAs("Trino $expr").isEqualTo(expected)
        }
        assertThat(DorisTestCluster.queryScalar("SELECT upper('\u00B5')")).isEqualTo("\u039C")
        assertThat(DorisTestCluster.queryScalar("SELECT upper('ß')")).isEqualTo("ß")
        assertThat(DorisTestCluster.queryScalar("SELECT lower('ΑΣ')")).isEqualTo("ασ")
        assertThat(DorisTestCluster.queryScalar("SELECT lower('\u212A')")).isEqualTo("k")
        assertThat(DorisTestCluster.queryScalar("SELECT upper('\u017F')")).isEqualTo("S")
        assertThat(DorisTestCluster.queryScalar("SELECT lower('\u0130')")).isEqualTo("i")
    }

    // --- item 5: length -> char_length ---

    @Test
    fun testLengthRendersCharLengthAndCountsCharacters() {
        assertPushedAndIdentical("SELECT id, length(v) FROM doris.b1_scalar.t")
        assertPushedAndIdentical("SELECT id FROM doris.b1_scalar.t WHERE length(v) = 3")
        val statement = remoteSql("SELECT id FROM doris.b1_scalar.t WHERE length(v) = 3")
        assertThat(statement).contains("char_length(`v`)").doesNotContain(" length(")
        // multibyte pins: emoji=1, CJK=2, NFD=2 — matches Trino character counts exactly
        assertThat(rows(session, "SELECT id, length(v) FROM doris.b1_scalar.t WHERE id IN (5, 6, 7)"))
            .isEqualTo(rows(local, "SELECT id, length(v) FROM doris.b1_scalar.t WHERE id IN (5, 6, 7)"))
        assertThat(computeActual("SELECT length(v) FROM doris.b1_scalar.t WHERE id = 5").onlyValue).isEqualTo(1L) // 😀
        assertThat(DorisTestCluster.queryScalar("SELECT char_length(v) FROM b1_scalar.t WHERE id = 5")).isEqualTo("1")
    }

    // --- item 6: starts_with -> escaped LIKE-prefix ---

    @Test
    fun testStartsWithPushesAsEscapedLikePrefix() {
        for (predicate in listOf(
            "starts_with(v, 'ab')",
            "starts_with(v, 'a%b')", // metacharacter in the prefix must match LITERALLY
            "starts_with(v, '')", // empty prefix: every non-NULL row
        )) {
            assertPushedAndIdentical("SELECT id FROM doris.b1_scalar.t WHERE $predicate")
        }
        assertThat(remoteSql("SELECT id FROM doris.b1_scalar.t WHERE starts_with(v, 'a%b')"))
            .contains("""LIKE 'a\\%b%'""")
        // exact rows: 'a%bc' matches the literal-% prefix; 'aXbc' must NOT
        assertThat(rows(session, "SELECT id FROM doris.b1_scalar.t WHERE starts_with(v, 'a%b')"))
            .isEqualTo(listOf("[8]"))
        // NULL_ONLY: stays local (and correct)
        val nullOnly = modeSession("NULL_ONLY")
        assertThat(query(nullOnly, "SELECT id FROM doris.b1_scalar.t WHERE starts_with(v, 'ab')"))
            .isNotFullyPushedDown(FilterNode::class.java)
        assertThat(rows(nullOnly, "SELECT id FROM doris.b1_scalar.t WHERE starts_with(v, 'ab')"))
            .isEqualTo(rows(session, "SELECT id FROM doris.b1_scalar.t WHERE starts_with(v, 'ab')"))
    }

    @Test
    fun testDeniedScalarShapesStayLocalAndCorrect() {
        for (sql in listOf(
            // REAL/DOUBLE operands are outside the value-type set
            "SELECT id, coalesce(d, 0e0) FROM doris.b1_scalar.t",
            // CHAR operands excluded (trailing-space divergence)
            "SELECT id, coalesce(c, 'x') FROM doris.b1_scalar.t",
        )) {
            assertThat(rows(session, sql)).describedAs(sql).isEqualTo(rows(local, sql))
            assertThat(query(sql)).describedAs(sql).isNotFullyPushedDown(ProjectNode::class.java)
        }
        // non-constant starts_with prefix: stays a local filter
        val nonConstant = "SELECT id FROM doris.b1_scalar.t WHERE starts_with(v, v)"
        assertThat(rows(session, nonConstant)).isEqualTo(rows(local, nonConstant))
        assertThat(query(nonConstant)).isNotFullyPushedDown(FilterNode::class.java)
    }

    companion object {
        private fun provisionFixture() {
            DorisTestCluster.executeAsRoot(
                "DROP DATABASE IF EXISTS b1_scalar",
                "CREATE DATABASE b1_scalar",
                """
                CREATE TABLE b1_scalar.t (
                    id INT NOT NULL, n INT, m INT, v VARCHAR(50), c CHAR(10), d DOUBLE,
                    dt0 DATETIME, dt6 DATETIME(6)
                ) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                INSERT INTO b1_scalar.t VALUES
                (1, 5, 10, 'abc', 'abc', 1.5, '2026-07-19 14:30:45', '2026-07-19 14:30:45.123456'),
                (2, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
                (3, 0, 5, 'ABC', 'x', 0.5, '0000-01-01 00:00:00', '0000-01-01 00:00:00'),
                (4, -7, NULL, 'abcd', 'y', -1.0, '9999-12-31 23:59:59', '9999-12-31 23:59:59.999999'),
                (5, 1, 1, '😀', 'e', 2.0, '2026-01-01 00:00:00', '2026-01-01 00:00:00.000001'),
                (6, 2, 2, '中文', 'f', 3.0, '2026-03-31 23:59:59', '2026-03-31 23:59:59.500000'),
                (7, 3, 3, concat('e', unhex('CC81')), 'g', 4.0, '2026-06-15 06:07:08', '2026-06-15 06:07:08.999999'),
                (8, 4, 4, 'a%bc', 'h', 5.0, '2026-11-01 01:30:00', '2026-11-01 01:30:00'),
                (9, 5, 5, 'aXbc', 'i', 6.0, '2026-12-31 23:59:00', '2026-12-31 23:59:00')
                """.trimIndent(),
            )
        }
    }
}
