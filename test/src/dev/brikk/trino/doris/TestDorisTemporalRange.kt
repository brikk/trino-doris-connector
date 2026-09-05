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
import io.trino.sql.planner.plan.PlanNode
import io.trino.sql.planner.plan.ProjectNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisTemporalRange : AbstractTestQueryFramework() {
    private val outsideBounds = listOf(
        Triple("d", "DATE '-0001-12-31'", "DATE '10000-01-01'"),
        Triple("dt0", "TIMESTAMP '-0001-12-31 23:59:59'", "TIMESTAMP '10000-01-01 00:00:00'"),
        Triple("dt3", "TIMESTAMP '-0001-12-31 23:59:59.999'", "TIMESTAMP '10000-01-01 00:00:00.000'"),
        Triple("dt6", "TIMESTAMP '-0001-12-31 23:59:59.999999'", "TIMESTAMP '10000-01-01 00:00:00.000000'"),
    )

    override fun createQueryRunner(): QueryRunner {
        DorisTestCluster.executeAsRoot(
            "DROP DATABASE IF EXISTS e06_temporal",
            "CREATE DATABASE e06_temporal",
            """
            CREATE TABLE e06_temporal.t (
                id INT NOT NULL, d DATE, dt0 DATETIME, dt3 DATETIME(3), dt6 DATETIME(6),
                ad ARRAY<DATE>, adt ARRAY<DATETIME(6)>
            ) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
            """.trimIndent(),
            """
            INSERT INTO e06_temporal.t VALUES
            (1, '0000-01-01', '0000-01-01 00:00:00', '0000-01-01 00:00:00', '0000-01-01 00:00:00',
             ['0000-01-01'], ['0000-01-01 00:00:00']),
            (2, '2026-01-01', '2026-01-01 12:00:00', '2026-01-01 12:00:00.123', '2026-01-01 12:00:00.123456',
             ['2026-01-01', NULL], ['2026-01-01 12:00:00.123456', NULL]),
            (3, '9999-12-31', '9999-12-31 23:59:59', '9999-12-31 23:59:59.999', '9999-12-31 23:59:59.999999',
             ['9999-12-31'], ['9999-12-31 23:59:59.999999']),
            (4, NULL, NULL, NULL, NULL, NULL, NULL),
            (5, NULL, NULL, NULL, NULL, [], []),
            (6, NULL, NULL, NULL, NULL, [NULL], [NULL])
            """.trimIndent(),
        )
        return DorisQueryRunner.builder().build()
    }

    private val local: Session
        get() = Session.builder(session).setSystemProperty("allow_pushdown_into_connectors", "false").build()

    @Test
    fun testOutOfRangeDomainsStayLocal() {
        for ((column, lower, upper) in outsideBounds) {
            val valid = if (column == "d") "DATE '0000-01-01'" else "TIMESTAMP '0000-01-01 00:00:00'"
            for (bound in listOf(lower, upper)) {
                for (operator in listOf("=", "<>", "<", "<=", ">", ">=")) {
                    assertLocal("SELECT id FROM doris.e06_temporal.t WHERE $column $operator $bound", FilterNode::class.java)
                }
                for (predicate in listOf("$column IN ($bound, $valid)", "$column NOT IN ($bound, $valid)", "$column = $bound OR $column IS NULL")) {
                    assertLocal("SELECT id FROM doris.e06_temporal.t WHERE $predicate", FilterNode::class.java)
                }
            }
            assertLocal("SELECT id FROM doris.e06_temporal.t WHERE $column BETWEEN $lower AND $upper", FilterNode::class.java)
        }
        // These returned no rows before the fix, instead of all three non-NULL dates.
        assertThat(query("SELECT id FROM doris.e06_temporal.t WHERE d < DATE '10000-01-01'")).matches("VALUES 1, 2, 3")
        assertThat(query("SELECT id FROM doris.e06_temporal.t WHERE d > DATE '-0001-12-31'")).matches("VALUES 1, 2, 3")
    }

    @Test
    fun testOutOfRangeScalarExpressionsStayLocal() {
        for ((column, lower, upper) in outsideBounds) {
            for (bound in listOf(lower, upper)) {
                for (function in listOf("coalesce", "nullif")) {
                    assertLocal("SELECT id, $function($column, $bound) FROM doris.e06_temporal.t", ProjectNode::class.java)
                }
                assertLocal("SELECT id FROM doris.e06_temporal.t WHERE nullif($column, $bound) IS NULL", FilterNode::class.java)
            }
            assertLocal("SELECT id FROM doris.e06_temporal.t WHERE coalesce($column, $lower) < $upper", FilterNode::class.java)
        }
    }

    @Test
    fun testOutOfRangeArrayLiteralsStayLocal() {
        for ((scalar, lower, upper) in outsideBounds.filter { it.first in listOf("d", "dt6") }) {
            val column = if (scalar == "d") "ad" else "adt"
            val valid = if (scalar == "d") "DATE '0000-01-01'" else "TIMESTAMP '0000-01-01 00:00:00.000000'"
            for (literal in listOf(lower, upper)) {
                for (predicate in listOf(
                    "contains($column, $literal)",
                    "array_position($column, $literal) = 0",
                    "NOT (array_position($column, $literal) = 0)",
                    "arrays_overlap($column, ARRAY[$literal])",
                    "arrays_overlap(ARRAY[$valid, $literal], $column)",
                )) {
                    assertLocal("SELECT id FROM doris.e06_temporal.t WHERE $predicate", FilterNode::class.java)
                }
            }
        }
        // Doris used to cast this needle to NULL and incorrectly match rows 2 and 6.
        assertThat(computeActual("SELECT id FROM doris.e06_temporal.t WHERE contains(ad, DATE '10000-01-01')").rowCount).isZero()
    }

    @Test
    fun testValidBoundariesAndNullChecksStillPush() {
        for ((column, lower, upper) in listOf(
            Triple("d", "DATE '0000-01-01'", "DATE '9999-12-31'"),
            Triple("dt0", "TIMESTAMP '0000-01-01 00:00:00'", "TIMESTAMP '9999-12-31 23:59:59'"),
            Triple("dt3", "TIMESTAMP '0000-01-01 00:00:00.000'", "TIMESTAMP '9999-12-31 23:59:59.999'"),
            Triple("dt6", "TIMESTAMP '0000-01-01 00:00:00.000000'", "TIMESTAMP '9999-12-31 23:59:59.999999'"),
        )) {
            for (predicate in listOf("$column = $lower", "$column = $upper", "$column BETWEEN $lower AND $upper", "$column IS NULL", "$column IS NOT NULL")) {
                assertThat(query("SELECT id FROM doris.e06_temporal.t WHERE $predicate")).describedAs(predicate).isFullyPushedDown()
            }
            assertThat(query("SELECT coalesce($column, $lower) FROM doris.e06_temporal.t")).isFullyPushedDown()
        }
        assertThat(query("SELECT id FROM doris.e06_temporal.t WHERE contains(ad, DATE '0000-01-01')")).isFullyPushedDown()
        assertThat(query("SELECT id FROM doris.e06_temporal.t WHERE contains(adt, TIMESTAMP '9999-12-31 23:59:59.999999')")).isFullyPushedDown()
    }

    @Test
    fun testSafeConjunctStillPushesWithoutTheOutOfRangeLiteral() {
        val sql = "SELECT id FROM doris.e06_temporal.t WHERE id = 2 AND d < DATE '10000-01-01'"
        assertThat(query(sql)).matches("VALUES 2")
        val execution = getDistributedQueryRunner().executeWithPlan(session, sql)
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        val remote = DorisTestCluster.awaitAuditLogStatements(marker, 60_000).single { it.contains("e06_temporal") }
        assertThat(remote).contains("`id` = 2").doesNotContain("10000")
    }

    private fun assertLocal(sql: String, node: Class<out PlanNode>) {
        assertThat(query(sql)).describedAs(sql).isNotFullyPushedDown(node)
        val expected = computeActual(local, sql).materializedRows.map { it.toString() }.sorted()
        val actual = computeActual(session, sql).materializedRows.map { it.toString() }.sorted()
        assertThat(actual).describedAs(sql).isEqualTo(expected)
    }
}
