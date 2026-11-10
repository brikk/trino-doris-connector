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
import io.trino.sql.planner.plan.FilterNode
import io.trino.sql.planner.plan.ProjectNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Golden 4.1.5 counterexamples: never infer ASCII column contents from VARCHAR metadata. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisCaseFoldLocal : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        DorisTestCluster.requireGoldenVersion()
        DorisTestCluster.executeAsRoot(
            "CREATE DATABASE IF NOT EXISTS golden_415_json",
            "DROP TABLE IF EXISTS golden_415_json.casefold_local",
            "CREATE TABLE golden_415_json.casefold_local (id INT, v VARCHAR(50)) DUPLICATE KEY(id) " +
                "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')",
            "INSERT INTO golden_415_json.casefold_local VALUES " +
                "(1,'ß'),(2,'ΑΣ'),(3,'İ'),(4,'ABC'),(5,NULL),(6,'SS'),(7,'i')",
        )
        return DorisQueryRunner.builder().build()
    }

    private val local: Session get() = fullyLocalSession(session)

    @Test
    fun testProjectionPredicateAndCompositionRemainLocalInEveryMode() {
        for (mode in DorisStringPushdownMode.entries) {
            val pushed = Session.builder(session).setCatalogSessionProperty("doris", "string_pushdown_mode", mode.name).build()
            for (projection in listOf("lower(v)", "upper(v)", "upper(coalesce(v,'absent'))", "length(lower(v))")) {
                val sql = "SELECT id,$projection FROM doris.golden_415_json.casefold_local"
                assertThat(computeActual(pushed, sql).materializedRows.toSet()).describedAs("$mode $projection")
                    .isEqualTo(computeActual(local, sql).materializedRows.toSet())
                assertThat(query(pushed, sql)).isNotFullyPushedDown(ProjectNode::class.java)
            }
            for (predicate in listOf("upper(v)='SS'", "lower(v)='i'", "upper(coalesce(v,'absent'))='SS'")) {
                val sql = "SELECT id FROM doris.golden_415_json.casefold_local WHERE $predicate"
                assertThat(computeActual(pushed, sql).materializedRows.toSet()).describedAs("$mode $predicate")
                    .isEqualTo(computeActual(local, sql).materializedRows.toSet())
                assertThat(query(pushed, sql)).isNotFullyPushedDown(FilterNode::class.java)
            }
            val grouped = "SELECT lower(v),count(*) FROM doris.golden_415_json.casefold_local GROUP BY lower(v)"
            assertThat(computeActual(pushed, grouped).materializedRows.toSet())
                .isEqualTo(computeActual(local, grouped).materializedRows.toSet())
            assertThat(query(pushed, grouped)).isNotFullyPushedDown(AggregationNode::class.java, ProjectNode::class.java)
        }
    }

    @Test
    fun testTrinoResultsAndRemoteSqlPinTheFallback() {
        assertThat(query("SELECT upper(v) FROM doris.golden_415_json.casefold_local WHERE id=1"))
            .matches("VALUES CAST('ß' AS VARCHAR(50))")
        assertThat(query("SELECT lower(v) FROM doris.golden_415_json.casefold_local WHERE id=2"))
            .matches("VALUES CAST('ασ' AS VARCHAR(50))")
        assertThat(query("SELECT lower(v) FROM doris.golden_415_json.casefold_local WHERE id=3"))
            .matches("VALUES CAST('i' AS VARCHAR(50))")
        val execution = getDistributedQueryRunner().executeWithPlan(session,
            "SELECT upper(coalesce(v,'absent')),length(lower(v)) FROM doris.golden_415_json.casefold_local")
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        val statements = DorisTestCluster.awaitAuditLogStatements(marker, 60_000)
            .filter { it.contains("casefold_local") }
        assertThat(statements).isNotEmpty.allSatisfy { assertThat(it).doesNotContain("lower(", "upper(") }
    }
}
