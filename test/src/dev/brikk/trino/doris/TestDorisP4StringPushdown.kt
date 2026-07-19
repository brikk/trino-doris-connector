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
import io.trino.sql.planner.plan.TopNNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Configurable string pushdown modes (`doris.string-pushdown.mode` / `string_pushdown_mode`)
 * over the adversarial fixture from `REPORT-string-comparison-probe-4.1.3.md`. Both engines'
 * semantics are PINNED here; each mode is differential-tested against NULL_ONLY (the local
 * ground truth) and the direct Doris oracle, with plan-shape and audit-log assertions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisP4StringPushdown : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    private fun modeSession(mode: DorisStringPushdownMode): Session = Session.builder(session)
        .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "string_pushdown_mode", mode.name)
        .build()

    private fun ids(session: Session, predicate: String): List<Any?> =
        computeActual(session, "SELECT id FROM doris.p4_strings.t WHERE $predicate ORDER BY id").onlyColumn.toList()

    // --- semantics pins (the safety arguments live here) ---

    @Test
    fun testDefaultModeIsGuarded() {
        val mode = computeActual("SHOW SESSION LIKE 'doris.string\\_pushdown\\_mode' ESCAPE '\\'").materializedRows
        assertThat(mode).hasSize(1)
        assertThat(mode.single().getField(1).toString()).isEqualTo("GUARDED")
    }

    @Test
    fun testTrinoStringSemanticsPin() {
        assertThat(computeActual("SELECT 'a' = 'A'").onlyValue).isEqualTo(false)
        assertThat(computeActual("SELECT 'a' = 'a '").onlyValue).isEqualTo(false)
        assertThat(computeActual("SELECT U&'\\00E9' = U&'e\\0301'").onlyValue).isEqualTo(false) // NFC != NFD
        assertThat(computeActual("SELECT CAST('a' AS char(10)) = CAST('a ' AS char(10))").onlyValue)
            .isEqualTo(true) // CHAR comparisons are pad/trim-insensitive in Trino — the CHAR hazard
        assertThat(computeActual("""SELECT 'a\b' LIKE 'a\b'""").onlyValue).isEqualTo(true) // backslash is LITERAL in Trino LIKE
        assertThat(computeActual("""SELECT 'a%b' LIKE 'a\b'""").onlyValue).isEqualTo(false)
    }

    @Test
    fun testDorisByteSemanticsPin() {
        // The collation regime is binary — the BINARY mode's verified assumption.
        assertThat(DorisTestCluster.queryScalar("SELECT @@collation_connection")).isEqualTo("utf8mb4_0900_bin")
        // Byte-exact equality (case, padding, unicode normalization all distinguish).
        assertThat(DorisTestCluster.querySingleColumn("SELECT id FROM p4_strings.t WHERE v = 'a'")).containsExactly("1")
        assertThat(DorisTestCluster.querySingleColumn("SELECT id FROM p4_strings.t WHERE v = 'A'")).containsExactly("2")
        // The CHAR under-return: stored 'a ' (id 3) does NOT match remote c = 'a' — while
        // Trino's CHAR semantics (trimmed) match it locally.
        assertThat(DorisTestCluster.querySingleColumn("SELECT id FROM p4_strings.t WHERE c = 'a' ORDER BY id"))
            .containsExactly("1", "9")
    }

    // --- GUARDED (the default): evidence-tiered — proven shapes push fully, hazards skip ---

    @Test
    fun testGuardedIsResultIdenticalToNullOnlyOverTheHazardMatrix() {
        val guarded = modeSession(DorisStringPushdownMode.GUARDED)
        val nullOnly = modeSession(DorisStringPushdownMode.NULL_ONLY)
        val predicates = listOf(
            "v = 'a'",
            "v = 'a '",
            "v = ' a'",
            "v < 'b'",
            "v > 'a'",
            "v BETWEEN 'A' AND 'b'",
            "v IN ('a', 'B')",
            "v = U&'\\00E9'", // NFC
            "v = U&'e\\0301'", // NFD
            "v = U&'\\+01F600'", // 4-byte emoji
            "v = U&'a\\200Bb'", // zero-width space
            "v = '${"x".repeat(190)}y'", // long
            "v = U&'a\\0000b'", // NUL literal (skip-detected)
            "c = 'a'", // CHAR (categorically not pushed in GUARDED)
            "s = 'a'",
            "v IS NULL",
            "v IS NOT NULL",
        )
        for (predicate in predicates) {
            assertThat(ids(guarded, predicate)).describedAs(predicate).isEqualTo(ids(nullOnly, predicate))
        }
        // spot-check absolute truth for the tricky rows
        assertThat(ids(guarded, "v = U&'a\\0000b'")).isEqualTo(listOf(9))
        assertThat(ids(guarded, "c = 'a'")).isEqualTo(listOf(1, 3, 9)) // Trino CHAR semantics: trimmed
    }

    @Test
    fun testGuardedPushesProvenShapesFully() {
        // evidence-tiered GUARDED: byte-exactness-proven domain shapes push with NO
        // retained filter (probe report: equality/range/IN rows) — the plan collapses
        val guarded = modeSession(DorisStringPushdownMode.GUARDED)
        for (predicate in listOf("v = 'a'", "v <> 'a'", "v < 'b'", "v BETWEEN 'A' AND 'b'", "v IN ('a', 'B')")) {
            assertThat(query(guarded, "SELECT id FROM doris.p4_strings.t WHERE $predicate"))
                .describedAs(predicate)
                .isFullyPushedDown()
        }
        assertThat(remoteSql(guarded, "SELECT id FROM doris.p4_strings.t WHERE v = 'a'"))
            .contains("`v` = 'a'")
    }

    @Test
    fun testGuardedStringEqualityCollapsesWithLimit() {
        // the motivating case: WHERE string = ... LIMIT n as ONE remote scan with remote LIMIT
        val guarded = modeSession(DorisStringPushdownMode.GUARDED)
        val sql = "SELECT id FROM doris.p4_strings.t WHERE v = 'a' LIMIT 100"
        assertThat(query(guarded, sql)).isFullyPushedDown()
        assertThat(remoteSql(guarded, sql)).contains("`v` = 'a'").contains("LIMIT 100")
        // genuine superset pre-filters do NOT collapse: LIKE-prefix keeps the local LIKE
        // (and therefore a local limit) — the engine retains the LIKE expression itself
        val like = "SELECT id FROM doris.p4_strings.t WHERE v LIKE 'a%' LIMIT 100"
        assertThat(query(guarded, like)).isNotFullyPushedDown(FilterNode::class.java)
        assertThat(remoteSql(guarded, like)).contains("`v` >= 'a'").doesNotContain("LIMIT 100")
    }

    @Test
    fun testGuardedSkipsHazardousShapesEntirely() {
        val guarded = modeSession(DorisStringPushdownMode.GUARDED)
        // NUL-bearing literal: no remote predicate at all (a retained filter could not
        // resurrect under-returned rows), still correct via the local filter.
        val nulSql = "SELECT id FROM doris.p4_strings.t WHERE v = U&'a\\0000b'"
        assertThat(query(guarded, nulSql)).isNotFullyPushedDown(FilterNode::class.java)
        assertThat(remoteSql(guarded, nulSql)).doesNotContain("`v` =")
        // CHAR column: categorically local in GUARDED (undetectable trailing-space hazard).
        val charSql = "SELECT id FROM doris.p4_strings.t WHERE c = 'a'"
        assertThat(query(guarded, charSql)).isNotFullyPushedDown(FilterNode::class.java)
        assertThat(remoteSql(guarded, charSql)).doesNotContain("`c` =")
    }

    // --- BINARY / FULL: full pushdown (verified byte semantics / caller-asserted) ---

    @Test
    fun testBinaryAndFullFullyPushSafeShapes() {
        for (mode in listOf(DorisStringPushdownMode.BINARY, DorisStringPushdownMode.FULL)) {
            val session = modeSession(mode)
            assertThat(query(session, "SELECT id FROM doris.p4_strings.t WHERE v = 'a'")).isFullyPushedDown()
            assertThat(query(session, "SELECT id FROM doris.p4_strings.t WHERE v BETWEEN 'A' AND 'b'")).isFullyPushedDown()
            assertThat(query(session, "SELECT id FROM doris.p4_strings.t WHERE v IN ('a', 'B')")).isFullyPushedDown()
            // byte-exact differential vs local truth on non-hazard shapes
            for (predicate in listOf("v = 'a'", "v = 'a '", "v < 'b'", "v = U&'\\+01F600'", "s = 'a'")) {
                assertThat(ids(session, predicate))
                    .describedAs("$mode $predicate")
                    .isEqualTo(ids(modeSession(DorisStringPushdownMode.NULL_ONLY), predicate))
            }
            assertThat(remoteSql(session, "SELECT id FROM doris.p4_strings.t WHERE v = 'a'")).contains("`v` = 'a'")
        }
    }

    @Test
    fun testFullModeDocumentedDivergences() {
        // Caller-asserted territory: probe-flagged divergences surface (or don't) in FULL.
        val full = modeSession(DorisStringPushdownMode.FULL)
        // NUL literal: byte-exact on reproduction — matches [9] like local truth. (One probe
        // run observed wrong-empty under host memory pressure; GUARDED keeps its skip as
        // defense-in-depth, but FULL correctness holds. See probe report.)
        assertThat(ids(full, "v = U&'a\\0000b'")).isEqualTo(listOf(9))
        // CHAR trailing-space data: local truth {1,3,9}; byte compare misses stored 'a '.
        // This is THE documented FULL divergence.
        assertThat(ids(full, "c = 'a'")).isEqualTo(listOf(1, 9))
    }

    @Test
    fun testSessionOverrideWorksInBothDirections() {
        // catalog default is GUARDED; loosen to FULL per query...
        assertThat(query(modeSession(DorisStringPushdownMode.FULL), "SELECT id FROM doris.p4_strings.t WHERE v = 'a'"))
            .isFullyPushedDown()
        // ... or tighten to NULL_ONLY: no value predicate reaches Doris at all.
        val nullOnly = modeSession(DorisStringPushdownMode.NULL_ONLY)
        val sql = "SELECT id FROM doris.p4_strings.t WHERE v = 'a'"
        assertThat(query(nullOnly, sql)).isNotFullyPushedDown(FilterNode::class.java)
        assertThat(remoteSql(nullOnly, sql)).doesNotContain("`v` =")
    }

    // --- string TopN (BINARY/FULL only; byte order == Trino codepoint order) ---

    @Test
    fun testStringTopNEligibilityPerMode() {
        val topN = "SELECT v FROM doris.p4_strings.t ORDER BY v ASC NULLS LAST LIMIT 12"
        // FULL/BINARY: pushed, and the ordering differential over the adversarial rows IS the
        // byte-vs-codepoint order equivalence proof.
        for (mode in listOf(DorisStringPushdownMode.BINARY, DorisStringPushdownMode.FULL)) {
            assertThat(query(modeSession(mode), topN)).ordered().isFullyPushedDown()
            assertThat(computeActual(modeSession(mode), topN).onlyColumn.toList())
                .describedAs(mode.name)
                .isEqualTo(computeActual(modeSession(DorisStringPushdownMode.NULL_ONLY), topN).onlyColumn.toList())
        }
        // GUARDED/NULL_ONLY: TopN stays local.
        for (mode in listOf(DorisStringPushdownMode.GUARDED, DorisStringPushdownMode.NULL_ONLY)) {
            assertThat(query(modeSession(mode), topN)).ordered().isNotFullyPushedDown(TopNNode::class.java)
        }
        // CHAR sort keys: never pushed (Trino orders trimmed values, Doris orders stored bytes).
        assertThat(query(modeSession(DorisStringPushdownMode.FULL), "SELECT id FROM doris.p4_strings.t ORDER BY c, id LIMIT 3"))
            .ordered().isNotFullyPushedDown(TopNNode::class.java)
    }

    // --- LIKE (BINARY/FULL only; backslash-doubling / ESCAPE passthrough per probe) ---

    @Test
    fun testLikePushdownPerMode() {
        val full = modeSession(DorisStringPushdownMode.FULL)
        val nullOnly = modeSession(DorisStringPushdownMode.NULL_ONLY)
        for (predicate in listOf(
            "v LIKE 'a%'",
            "v LIKE '_'",
            """v LIKE 'a\%'""", // Trino: backslash literal + wildcard -> Doris pattern gets doubled
            "v LIKE 'a!%b' ESCAPE '!'", // explicit escape passes through
        )) {
            val sql = "SELECT id FROM doris.p4_strings.t WHERE $predicate"
            assertThat(query(full, sql)).describedAs(predicate).isFullyPushedDown()
            assertThat(ids(full, predicate)).describedAs(predicate).isEqualTo(ids(nullOnly, predicate))
        }
        // exact expected rows for the divergence-prone patterns
        assertThat(ids(full, """v LIKE 'a\%'""")).isEqualTo(listOf(17))
        assertThat(ids(full, "v LIKE 'a!%b' ESCAPE '!'")).isEqualTo(listOf(15))
        // remote shape: backslash doubled for Doris's default-escape semantics
        assertThat(remoteSql(full, """SELECT id FROM doris.p4_strings.t WHERE v LIKE 'a\%'"""))
            .contains("""LIKE 'a\\\\%'""")
        // wildcard-free LIKE never reaches the rule: the engine folds it to an equality
        // domain, which ships through the (FULL) domain path as a plain comparison
        assertThat(ids(full, """v LIKE 'a\b'""")).isEqualTo(listOf(17))
        assertThat(remoteSql(full, """SELECT id FROM doris.p4_strings.t WHERE v LIKE 'a\b'"""))
            .contains("""`v` = 'a\\b'""")
        // GUARDED: LIKE stays local (and correct)
        val guarded = modeSession(DorisStringPushdownMode.GUARDED)
        val guardedLike = "SELECT id FROM doris.p4_strings.t WHERE v LIKE 'a%'"
        assertThat(query(guarded, guardedLike)).isNotFullyPushedDown(FilterNode::class.java)
        assertThat(ids(guarded, "v LIKE 'a%'")).isEqualTo(ids(nullOnly, "v LIKE 'a%'"))
    }

    // --- helpers ---

    private fun remoteSql(session: Session, sql: String): String {
        val execution = getDistributedQueryRunner().executeWithPlan(session, sql)
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        val statements = DorisTestCluster.awaitAuditLogStatements(marker, 60_000)
        return statements.single { it.contains("p4_strings") }
    }

    companion object {
        private fun provisionFixture() {
            DorisTestCluster.executeAsRoot(
                "DROP DATABASE IF EXISTS p4_strings",
                "CREATE DATABASE p4_strings",
                """
                CREATE TABLE p4_strings.t (id INT NOT NULL, v VARCHAR(200), c CHAR(10), s STRING)
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
            )
            // Parameterized inserts: NUL/zero-width/control characters must travel byte-exactly
            // (the probe verified storage fidelity via hex()).
            val values = listOf(
                1 to "a", 2 to "A", 3 to "a ", 4 to " a",
                5 to "\u00e9", // é NFC
                6 to "e\u0301", // é NFD
                7 to "\ud83d\ude00", // 😀
                8 to "a\u200bb", // zero-width space
                9 to "a\u0000b", // NUL (CHAR column truncates at NUL on write — storage note)
                10 to "a\tb",
                11 to "x".repeat(190) + "y",
                13 to "b", 14 to "B",
                15 to "a%b", 16 to "a_b", 17 to "a\\b", 18 to "A%B", 19 to "aXb", 20 to "ab",
            )
            DorisTestCluster.executePreparedAsRoot(
                "INSERT INTO p4_strings.t VALUES (?, ?, ?, ?)",
                values.map { (id, text) -> listOf(id, text, if (text.length <= 10) text else "long", text) } +
                    listOf(listOf(12, null, null, null)),
            )
        }
    }
}
