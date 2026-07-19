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
 * P5 batch: `json_extract_scalar` equality -> `json_unquote(json_extract(...))`,
 * `cardinality` comparisons -> `array_size(...)` (value-safe tier), and the GUARDED-mode
 * LIKE-prefix range pre-filter (engine-derived domain + retained filter). Truth tables for
 * both engines are PINNED here; probe verdicts in `NOTES-p5-batch.md`; evidence tuples in
 * [DorisPushdownEvidence.JSON_EXTRACT_SCALAR] / [DorisPushdownEvidence.CARDINALITY].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisP5Batch : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    private fun jsonIds(predicate: String): List<Any?> =
        computeActual("SELECT id FROM doris.p5_batch.j WHERE $predicate ORDER BY id").onlyColumn.toList()

    private fun arrIds(predicate: String): List<Any?> =
        computeActual("SELECT id FROM doris.p5_batch.arr WHERE $predicate ORDER BY id").onlyColumn.toList()

    /** Local-truth oracle: OR with an unpushable disjunct keeps the WHOLE predicate in Trino. */
    private fun jsonIdsLocal(predicate: String): List<Any?> =
        computeActual("SELECT id FROM doris.p5_batch.j WHERE ($predicate) OR random() < 0 ORDER BY id").onlyColumn.toList()

    private fun remoteSql(session: Session, sql: String): String {
        val execution = getDistributedQueryRunner().executeWithPlan(session, sql)
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        val statements = DorisTestCluster.awaitAuditLogStatements(marker, 60_000)
        return statements.single { it.contains("p5_batch") }
    }

    private fun remoteSql(sql: String): String = remoteSql(session, sql)

    // --- json_extract_scalar equality ---

    @Test
    fun testJsonEqualityIsPushedWithExactRemoteShape() {
        val sql = "SELECT id FROM doris.p5_batch.j WHERE json_extract_scalar(doc, '$.a') = 'x'"
        assertThat(query(sql)).isFullyPushedDown()
        assertThat(jsonIds("json_extract_scalar(doc, '$.a') = 'x'")).isEqualTo(listOf(1))
        assertThat(remoteSql(sql)).contains("(json_unquote(json_extract(`doc`, '$.a')) = 'x')")
        // '=' is symmetric: reversed orientation pushes too
        assertThat(query("SELECT id FROM doris.p5_batch.j WHERE 'x' = json_extract_scalar(doc, '$.a')"))
            .isFullyPushedDown()
        // nested and array-index simple paths
        assertThat(query("SELECT id FROM doris.p5_batch.j WHERE json_extract_scalar(doc, '$.n.k') = 'deep'"))
            .isFullyPushedDown()
        assertThat(jsonIds("json_extract_scalar(doc, '$.n.k') = 'deep'")).isEqualTo(listOf(40))
        assertThat(query("SELECT id FROM doris.p5_batch.j WHERE json_extract_scalar(doc, '$.arr2[1]') = 'second'"))
            .isFullyPushedDown()
        assertThat(jsonIds("json_extract_scalar(doc, '$.arr2[1]') = 'second'")).isEqualTo(listOf(41))
    }

    @Test
    fun testJsonEqualityDifferentialOverEveryAdversarialCell() {
        // pushed result == local-truth result for every literal, INCLUDING guarded-out ones
        // (those simply run local — correct either way)
        for (literal in listOf(
            "'x'", "'true'", "'false'", "'null'", "''", "'café'", "U&'caf\\00E9'", "'emoji😀'",
            "'q\"uote'", "'[1, 2]'", "'{\"b\":1}'", "'1'", "'1.1'", "'100'", "'1E+30'", "'-0'", "'0'",
        )) {
            val predicate = "json_extract_scalar(doc, '$.a') = $literal"
            assertThat(jsonIds(predicate)).describedAs(predicate).isEqualTo(jsonIdsLocal(predicate))
        }
    }

    @Test
    fun testJsonSemanticsPins() {
        // string scalar unquoted identically on both engines (case: emoji + escapes)
        assertThat(jsonIds("json_extract_scalar(doc, '$.a') = 'café'")).isEqualTo(listOf(11))
        assertThat(jsonIds("json_extract_scalar(doc, '$.a') = 'emoji😀'")).isEqualTo(listOf(23))
        assertThat(jsonIds("json_extract_scalar(doc, '$.a') = 'q\"uote'")).isEqualTo(listOf(13))
        // boolean scalars render 'true'/'false' on both engines — proven pushable
        assertThat(query("SELECT id FROM doris.p5_batch.j WHERE json_extract_scalar(doc, '$.a') = 'true'"))
            .isFullyPushedDown()
        assertThat(jsonIds("json_extract_scalar(doc, '$.a') = 'true'")).isEqualTo(listOf(9))
        // the STRING 'null' matches only the string cell — JSON null and missing key are SQL
        // NULL on BOTH engines (Doris json_unquote(json_extract) of JSON null is SQL NULL,
        // unlike MySQL's 'null' text — the live probe cell that makes '=' safe)
        assertThat(query("SELECT id FROM doris.p5_batch.j WHERE json_extract_scalar(doc, '$.a') = 'null'"))
            .isFullyPushedDown()
        assertThat(jsonIds("json_extract_scalar(doc, '$.a') = 'null'")).isEqualTo(listOf(14))
        // empty-string scalar is not NULL on either engine
        assertThat(jsonIds("json_extract_scalar(doc, '$.a') = ''")).isEqualTo(listOf(17))
    }

    @Test
    fun testJsonNumberCanonicalizationDivergenceEvidence() {
        // WHY numeric-looking literals never push: the engines canonicalize number text
        // independently. Trino (through the connector, local evaluation):
        assertThat(computeActual("SELECT json_extract_scalar(doc, '$.a') FROM doris.p5_batch.j WHERE id = 32").onlyValue)
            .isEqualTo("1E+30")
        assertThat(computeActual("SELECT json_extract_scalar(doc, '$.a') FROM doris.p5_batch.j WHERE id = 34").onlyValue)
            .isEqualTo("0")
        // Doris oracle for the SAME cells:
        assertThat(DorisTestCluster.queryScalar("SELECT json_unquote(json_extract(doc, '$.a')) FROM p5_batch.j WHERE id = 32"))
            .isEqualTo("1e+30")
        assertThat(DorisTestCluster.queryScalar("SELECT json_unquote(json_extract(doc, '$.a')) FROM p5_batch.j WHERE id = 34"))
            .isEqualTo("-0")
        // non-scalar cells: Trino NULL, Doris JSON text — WHY '<>'/IS-NULL forms and
        // '{'/'['-prefixed literals never push
        assertThat(computeActual("SELECT json_extract_scalar(doc, '$.a') FROM doris.p5_batch.j WHERE id = 4").onlyValue)
            .isNull()
        assertThat(DorisTestCluster.queryScalar("SELECT json_unquote(json_extract(doc, '$.a')) FROM p5_batch.j WHERE id = 4"))
            .isEqualTo("""{"b":1}""")
    }

    @Test
    fun testJsonGuardsKeepHazardShapesLocal() {
        for (predicate in listOf(
            "json_extract_scalar(doc, '$.a') <> 'x'", // non-scalar cells over-return remotely
            "json_extract_scalar(doc, '$.a') IS NULL", // ditto (Doris non-NULL for non-scalars)
            "json_extract_scalar(doc, '$.a') IS NOT NULL",
            "json_extract_scalar(doc, '$.a') = '{\"b\":1}'", // could equal Doris non-scalar text
            "json_extract_scalar(doc, '$.a') = '[1, 2]'",
            "json_extract_scalar(doc, '$.a') = '1'", // numeric-looking: canonicalization hazard
            "json_extract_scalar(doc, '$.a') = '1E+30'",
            "json_extract_scalar(doc, '$.a') = '-0'",
            "json_extract_scalar(doc, '$.a') = '.5'",
            "json_extract_scalar(doc, '$.a') = '1e3'",
            """json_extract_scalar(doc, '$["a.b"]') = 'dotted'""", // quoted-key path syntax differs
            "json_extract_scalar(doc, '$.a') = U&'a\\0000b'", // NUL policy
        )) {
            val sql = "SELECT id FROM doris.p5_batch.j WHERE $predicate"
            assertThat(query(sql)).describedAs(predicate).isNotFullyPushedDown(FilterNode::class.java)
            assertThat(remoteSql(sql)).describedAs(predicate).doesNotContain("json_unquote")
            // local evaluation is the ground truth — result correctness is trivially preserved
            assertThat(jsonIds(predicate)).describedAs(predicate).isEqualTo(jsonIdsLocal(predicate))
        }
    }

    // --- cardinality comparisons ---

    @Test
    fun testCardinalityComparisonsArePushed() {
        val cases = mapOf(
            "cardinality(a) = 3" to listOf(1, 3),
            "cardinality(a) <> 3" to listOf(2, 5),
            "cardinality(a) < 2" to listOf(2, 5),
            "cardinality(a) <= 1" to listOf(2, 5),
            "cardinality(a) > 1" to listOf(1, 3),
            "cardinality(a) >= 3" to listOf(1, 3),
            "cardinality(a) BETWEEN 1 AND 3" to listOf(1, 3, 5),
            "3 = cardinality(a)" to listOf(1, 3),
            "2 > cardinality(a)" to listOf(2, 5), // flipped orientation
        )
        for ((predicate, expected) in cases) {
            val sql = "SELECT id FROM doris.p5_batch.arr WHERE $predicate"
            assertThat(query(sql)).describedAs(predicate).isFullyPushedDown()
            assertThat(arrIds(predicate)).describedAs(predicate).isEqualTo(expected)
        }
        assertThat(remoteSql("SELECT id FROM doris.p5_batch.arr WHERE cardinality(a) = 3"))
            .contains("(array_size(`a`) = 3)")
        assertThat(remoteSql("SELECT id FROM doris.p5_batch.arr WHERE cardinality(a) BETWEEN 1 AND 3"))
            .contains("(array_size(`a`) BETWEEN 1 AND 3)")
    }

    @Test
    fun testCardinalitySemanticsPins() {
        // the value-identity cells backing the value-safe classification:
        // NULL array -> NULL (row 4 never matches any comparison), empty -> 0, NULL elements COUNTED
        assertThat(arrIds("cardinality(a) = 0")).isEqualTo(listOf(2))
        assertThat(arrIds("cardinality(a) IS NULL")).isEqualTo(listOf(4)) // IS NULL itself stays a domain shape
        assertThat(computeActual("SELECT cardinality(a) FROM doris.p5_batch.arr WHERE id = 3").onlyValue)
            .isEqualTo(3L) // [1,null,3] — NULLs counted
        // Doris oracle agreement on the same cells
        assertThat(DorisTestCluster.queryScalar("SELECT array_size(a) FROM p5_batch.arr WHERE id = 3")).isEqualTo("3")
        assertThat(DorisTestCluster.queryScalar("SELECT array_size(a) FROM p5_batch.arr WHERE id = 4")).isNull()
        assertThat(DorisTestCluster.queryScalar("SELECT array_size(a) FROM p5_batch.arr WHERE id = 2")).isEqualTo("0")
    }

    @Test
    fun testCardinalityIsValueSafeComposable() {
        val cases = mapOf(
            "NOT (cardinality(a) = 3)" to listOf(2, 5),
            // note: OR of two equalities over the SAME call folds to $in upstream (no $in
            // rule exists — stays local); mixed-operator OR reaches the composition tier
            "cardinality(a) = 3 OR cardinality(a) < 1" to listOf(1, 2, 3),
            "cardinality(a) = 3 AND array_position(a, 1) = 1" to listOf(1, 3),
            "NOT (cardinality(a) = 3 OR array_position(a, 7) = 1)" to listOf(2),
        )
        for ((predicate, expected) in cases) {
            val sql = "SELECT id FROM doris.p5_batch.arr WHERE $predicate"
            assertThat(query(sql)).describedAs(predicate).isFullyPushedDown()
            assertThat(arrIds(predicate)).describedAs(predicate).isEqualTo(expected)
        }
        // upstream canonicalization: NOT(=) becomes <>, and NOT(a OR b) De-Morgans into two
        // <> conjuncts — the NOT rule itself is exercised by the P3 pins; here we pin the
        // OR composition wire shape and the canonicalized NOT forms
        assertThat(remoteSql("SELECT id FROM doris.p5_batch.arr WHERE NOT (cardinality(a) = 3)"))
            .contains("(array_size(`a`) <> 3)")
        assertThat(remoteSql("SELECT id FROM doris.p5_batch.arr WHERE cardinality(a) = 3 OR cardinality(a) < 1"))
            .contains("((array_size(`a`) = 3) OR (array_size(`a`) < 1))")
    }

    @Test
    fun testCardinalityGuardsKeepUnprovenShapesLocal() {
        // ARRAY<DOUBLE> columns are outside the pushable-element allowlist (documented
        // over-restriction — see NOTES-p5-batch); cardinality over them stays local
        val sql = "SELECT id FROM doris.p5_batch.arr WHERE cardinality(d) = 1"
        assertThat(query(sql)).isNotFullyPushedDown(FilterNode::class.java)
        assertThat(remoteSql(sql)).doesNotContain("array_size")
        assertThat(arrIds("cardinality(d) = 1")).isEqualTo(listOf(1))
        // non-constant bound stays local
        assertThat(query("SELECT id FROM doris.p5_batch.arr WHERE cardinality(a) = id"))
            .isNotFullyPushedDown(FilterNode::class.java)
    }

    // --- GUARDED LIKE-prefix range pre-filter (engine-derived domain, retained filter) ---

    @Test
    fun testGuardedLikePrefixShipsRangePreFilterWithRetainedLike() {
        // default mode is GUARDED: Trino derives the prefix range domain from LIKE, the
        // GUARDED varchar controller ships it as a superset pre-filter and RETAINS the filter
        val sql = "SELECT id FROM doris.p5_batch.s WHERE v LIKE 'foo%'"
        assertThat(query(sql)).isNotFullyPushedDown(FilterNode::class.java) // LIKE retained locally
        assertThat(remoteSql(sql)).contains("(`v` >= 'foo' AND `v` < 'fop')")
        assertThat(computeActual("$sql ORDER BY id").onlyColumn.toList()).isEqualTo(listOf(1, 2, 3))
        // differential vs NULL_ONLY (nothing pushed — pure local truth)
        val nullOnly = Session.builder(session)
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "string_pushdown_mode", "NULL_ONLY")
            .build()
        assertThat(computeActual(nullOnly, "$sql ORDER BY id").onlyColumn.toList()).isEqualTo(listOf(1, 2, 3))
        assertThat(remoteSql(nullOnly, sql)).doesNotContain("`v` >=")
    }

    @Test
    fun testGuardedLikePrefixSuccessorEdgeAndNulPolicy() {
        // prefix ending in U+00FF: the engine widens the successor bound ('fp') — still a
        // correct superset; the retained LIKE restores exactness
        val sql = "SELECT id FROM doris.p5_batch.s WHERE v LIKE 'fo\u00ff%'"
        assertThat(remoteSql(sql)).contains("(`v` >= 'foÿ' AND `v` < 'fp')")
        assertThat(computeActual("$sql ORDER BY id").onlyColumn.toList()).isEqualTo(listOf(7))
        // NUL in the prefix: GUARDED's 0x00 skip keeps the whole predicate local
        val nulSql = "SELECT id FROM doris.p5_batch.s WHERE v LIKE U&'f\\0000%'"
        assertThat(remoteSql(nulSql)).doesNotContain("`v` >=")
        assertThat(computeActual(nulSql).rowCount).isEqualTo(0)
        // mid-string wildcard: prefix range up to the wildcard still pre-filters (superset)
        assertThat(remoteSql("SELECT id FROM doris.p5_batch.s WHERE v LIKE 'fo%X'"))
            .contains("(`v` >= 'fo' AND `v` < 'fp')")
    }

    @Test
    fun testFullModeStillPushesWholeLike() {
        // in BINARY/FULL the P4 LIKE rule wins — the whole LIKE goes remote, no pre-filter split
        val full = Session.builder(session)
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "string_pushdown_mode", "FULL")
            .build()
        val sql = "SELECT id FROM doris.p5_batch.s WHERE v LIKE 'foo%'"
        assertThat(query(full, sql)).isFullyPushedDown()
        assertThat(remoteSql(full, sql)).contains("LIKE 'foo%'")
    }

    companion object {
        private fun provisionFixture() {
            DorisTestCluster.executeAsRoot(
                "DROP DATABASE IF EXISTS p5_batch",
                "CREATE DATABASE p5_batch",
                """
                CREATE TABLE p5_batch.j (id INT NOT NULL, doc JSON)
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                CREATE TABLE p5_batch.arr (id INT NOT NULL, a ARRAY<INT>, d ARRAY<DOUBLE>)
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                CREATE TABLE p5_batch.s (id INT NOT NULL, v VARCHAR(200))
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
            )
            insertJsonRows()
            insertArrayAndStringRows()
        }

        private fun insertJsonRows() {
            DorisTestCluster.executePreparedAsRoot(
                "INSERT INTO p5_batch.j VALUES (?, ?)",
                listOf(
                    listOf(1, """{"a": "x"}"""),
                    listOf(2, """{"a": null}"""), // JSON null at path
                    listOf(3, """{"b": 1}"""), // missing key
                    listOf(4, """{"a": {"b": 1}}"""), // object at path
                    listOf(5, """{"a": [1, 2]}"""), // array at path
                    listOf(6, """{"a": 1}"""),
                    listOf(7, """{"a": 1.0}"""), // storage-canonicalized to 1 (both engines agree)
                    listOf(8, """{"a": 1.10}"""),
                    listOf(9, """{"a": true}"""),
                    listOf(10, """{"a": false}"""),
                    listOf(11, """{"a": "caf\u00e9"}"""), // unicode escape in JSON
                    listOf(12, """{"a": "tab\there"}"""),
                    listOf(13, """{"a": "q\"uote"}"""),
                    listOf(14, """{"a": "null"}"""), // STRING 'null' vs JSON null
                    listOf(15, """{"a": "[1, 2]"}"""), // STRING that looks like an array
                    listOf(16, """{"a": 1e2}"""),
                    listOf(17, """{"a": ""}"""),
                    listOf(21, null), // SQL NULL doc
                    listOf(23, """{"a": "emoji\ud83d\ude00"}"""), // surrogate-pair escape
                    listOf(30, """{"a": 123456789012345678901234567890}"""),
                    listOf(31, """{"a": 9223372036854775807}"""),
                    listOf(32, """{"a": 1e30}"""), // Doris '1e+30' vs Trino '1E+30'
                    listOf(33, """{"a": 1.5e-7}"""), // Doris '1.5e-07' vs Trino '1.5E-7'
                    listOf(34, """{"a": -0.0}"""), // Doris '-0' vs Trino '0'
                    listOf(35, """{"a": 0.30000000000000004}"""),
                    listOf(37, """{"a": 18446744073709551615}"""),
                    listOf(40, """{"n": {"k": "deep"}}"""), // nested path
                    listOf(41, """{"arr2": ["first", "second"]}"""), // array-index path
                    listOf(42, """{"a.b": "dotted"}"""), // dotted KEY (quoted-path syntax differs)
                ),
            )
        }

        private fun insertArrayAndStringRows() {
            DorisTestCluster.executePreparedAsRoot(
                "INSERT INTO p5_batch.arr VALUES (?, ?, ?)",
                listOf(
                    listOf(1, "[1, 2, 3]", "[1.5]"),
                    listOf(2, "[]", "[]"),
                    listOf(3, "[1, null, 3]", null),
                    listOf(4, null, null),
                    listOf(5, "[7]", "[1.5, 2.5]"),
                ),
            )
            DorisTestCluster.executePreparedAsRoot(
                "INSERT INTO p5_batch.s VALUES (?, ?)",
                listOf(
                    listOf(1, "foo"),
                    listOf(2, "foo "),
                    listOf(3, "fooX"),
                    listOf(4, "fop"),
                    listOf(5, "fo"),
                    listOf(6, "FOO"),
                    listOf(7, "fo\u00ffz"),
                    listOf(8, null),
                ),
            )
        }
    }
}
