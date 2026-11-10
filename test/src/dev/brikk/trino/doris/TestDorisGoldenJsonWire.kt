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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Native stored-column wire evidence, not an inference from CAST(JSON AS STRING). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisGoldenJsonWire : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        DorisTestCluster.requireGoldenVersion()
        DorisTestCluster.executeAsRoot(
            "CREATE DATABASE IF NOT EXISTS golden_415_json",
            "DROP TABLE IF EXISTS golden_415_json.wire",
            "CREATE TABLE golden_415_json.wire (id INT, doc JSON) DUPLICATE KEY(id) " +
                "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')",
            "DROP TABLE IF EXISTS golden_415_json.nonfinite",
            "CREATE TABLE golden_415_json.nonfinite (id INT, doc JSON) DUPLICATE KEY(id) " +
                "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')",
            """INSERT INTO golden_415_json.nonfinite VALUES (3,JSON_PARSE('{"n":1e9999}'))""",
            """
            INSERT INTO golden_415_json.wire VALUES
                (1,NULL), (2,JSON_PARSE('null')),
                (4,JSON_PARSE('{"n":1.2300}')),
                (5,JSON_PARSE('{"n":"inf"}')), (6,JSON_PARSE('{"n":"hello"}')),
                (7,JSON_PARSE('{"a":"first","a":"second"}'))
            """.trimIndent(),
        )
        return DorisQueryRunner.builder().build()
    }

    @Test
    fun testSqlNullAndJsonNullHaveDistinctNativeWireValues() {
        DorisTestCluster.openRootConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id,doc,CAST(doc AS STRING) FROM golden_415_json.wire WHERE id <= 2 ORDER BY id").use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString(2)).isNull()
                    assertThat(result.wasNull()).isTrue()
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString(2)).isEqualTo("null")
                    assertThat(result.wasNull()).isFalse()
                    assertThat(result.getString(3)).isNull()
                    assertThat(result.wasNull()).isTrue()
                    assertThat(result.next()).isFalse()
                }
            }
        }
        assertThat(query("SELECT id, doc IS NULL, json_format(doc) FROM doris.golden_415_json.wire WHERE id <= 2"))
            .matches("VALUES (1, true, CAST(NULL AS VARCHAR)), (2, false, VARCHAR 'null')")
    }

    @Test
    fun testStoredNonFiniteJsonFailsLoudRatherThanBecomingNullOrText() {
        assertThat(DorisTestCluster.queryScalar("SELECT doc FROM golden_415_json.nonfinite WHERE id = 3"))
            .isEqualTo("""{"n":inf}""")
        assertThatThrownBy { computeActual("SELECT json_format(doc) FROM doris.golden_415_json.nonfinite WHERE id = 3") }
            .hasMessageContaining("invalid JSON")
        val sql = "SELECT id FROM doris.golden_415_json.nonfinite WHERE id = 3 AND json_extract_scalar(doc, '$.n') = 'inf'"
        // QueryAssertions' plan helpers also execute an all-pushdown-OFF oracle;
        // inspect EXPLAIN directly for this intentionally unreadable relation.
        assertThat(computeActual("EXPLAIN $sql").onlyValue.toString())
            .contains("json_extract_scalar").doesNotContain("json_unquote")
        assertThatThrownBy { computeActual(sql) }.hasMessageContaining("invalid JSON")
    }

    @Test
    fun testReadableRowsAgreeWithLocalJsonEvaluation() {
        // The non-finite row is in a separate relation, outside both valid-input oracles.
        val local = fullyLocalSession(session)
        for (literal in listOf("hello", "inf", "1.23", "1.2300", "true", "null")) {
            val sql = "SELECT id FROM doris.golden_415_json.wire " +
                "WHERE id >= 4 AND json_extract_scalar(doc, '$.n') = '$literal' ORDER BY id"
            assertThat(computeActual(sql).materializedRows).describedAs(literal)
                .isEqualTo(computeActual(local, sql).materializedRows)
        }
        assertThat(query("SELECT id FROM doris.golden_415_json.wire WHERE id >= 4 AND json_extract_scalar(doc, '$.n') = 'inf'"))
            .isNotFullyPushedDown(FilterNode::class.java)
    }

    @Test
    fun testDorisNumericNormalizationAndFirstDuplicateSurviveTheWire() {
        // The connector preserves what Doris sends; it cannot recover source scale lost in Doris.
        assertThat(query("SELECT json_extract_scalar(doc, '$.n') FROM doris.golden_415_json.wire WHERE id = 4"))
            .matches("VALUES VARCHAR '1.23'")
        val sql = "SELECT id FROM doris.golden_415_json.wire WHERE id >= 4 AND json_extract_scalar(doc, '$.a') = 'first'"
        assertThat(query(sql)).isFullyPushedDown()
        assertThat(query(sql)).matches("VALUES 7")
        assertThat(query("SELECT json_format(doc) FROM doris.golden_415_json.wire WHERE id = 7"))
            .matches("""VALUES VARCHAR '{"a":"first","a":"second"}'""")
    }
}
