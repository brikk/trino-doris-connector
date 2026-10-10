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

import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.ResultSet

/** Release-upgrade pins; preserve existing pushdown exclusions until separately proven safe. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisReleaseCompatibility : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        DorisTestCluster.executeAsRoot(
            "DROP DATABASE IF EXISTS release_compat",
            "CREATE DATABASE release_compat",
            "CREATE TABLE release_compat.nullable_or (id BIGINT, j STRING, a BIGINT, b BIGINT) " +
                "DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num' = '1')",
            """
            INSERT INTO release_compat.nullable_or
            SELECT number,
                   CASE WHEN number % 100 = 0 THEN '{"flag": true}' ELSE concat('{"n": ', number, '}') END,
                   number + CASE number % 3 WHEN 0 THEN 10 WHEN 1 THEN -100 ELSE -200000 END,
                   number
            FROM numbers("number" = "100000")
            """.trimIndent(),
            """
            CREATE VIEW release_compat.case_branches AS
            SELECT id, CASE
                WHEN json_extract_bool(json_parse_error_to_null(j), '$.flag') OR a > b THEN 'then_b'
                WHEN a < b THEN 'then_a'
                ELSE 'else'
            END AS branch
            FROM release_compat.nullable_or
            """.trimIndent(),
        )
        return DorisQueryRunner.builder().build()
    }

    @Test
    fun testNullableOrCaseDoesNotCrashAndPreservesBranches() {
        // Doris #68401 (4.1.4.1): noncanonical nullable Boolean bytes could crash CASE.
        // Exercise both execution paths directly, then read a remote CASE view via Trino.
        for (shortCircuit in listOf(false, true)) {
            DorisTestCluster.openRootConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("SET enable_sql_cache = false")
                    statement.execute("SET short_circuit_evaluation = $shortCircuit")
                    statement.executeQuery(
                        "SELECT branch, count(*) FROM release_compat.case_branches GROUP BY branch ORDER BY branch",
                    ).use { result ->
                        assertThat(readBranchCounts(result)).describedAs("short_circuit_evaluation=$shortCircuit")
                            .isEqualTo(mapOf("then_a" to 66000L, "then_b" to 34000L))
                    }
                }
            }
        }
        assertThat(query("SELECT branch, count(*) FROM doris.release_compat.case_branches GROUP BY branch"))
            .matches("VALUES (CAST('then_a' AS VARCHAR), BIGINT '66000'), (CAST('then_b' AS VARCHAR), BIGINT '34000')")
    }

    @Test
    fun testNullableOrTruthTableMatchesTrino() {
        val values = listOf("false", "CAST(NULL AS BOOLEAN)", "true")
        for (left in values) {
            for (right in values) {
                val sql = "SELECT $left OR $right"
                val expected = computeActual(sql).onlyValue
                assertThat(readRemoteBoolean(sql)).describedAs(sql).isEqualTo(expected)
            }
        }
    }

    private fun readBranchCounts(result: ResultSet): Map<String, Long> = buildMap {
        while (result.next()) {
            put(result.getString(1), result.getLong(2))
        }
    }

    private fun readRemoteBoolean(sql: String): Boolean? = DorisTestCluster.openRootConnection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                assertThat(result.next()).isTrue()
                val value = result.getBoolean(1)
                if (result.wasNull()) null else value
            }
        }
    }
}
