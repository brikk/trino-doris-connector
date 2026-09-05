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

import io.trino.sql.planner.plan.FilterNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.Float.floatToIntBits
import java.sql.SQLDataException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisFloatSpecialValues : AbstractTestQueryFramework() {
    private val expected = listOf(
        Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN,
        3.402823e38f, -3.402823e38f, 0.0f, -0.0f, null, Float.MIN_VALUE,
        // Stored extrema have reduced wire precision; preserve the existing finite wire values.
        3.402823e38f, -3.402823e38f,
    )

    override fun createQueryRunner(): QueryRunner {
        DorisTestCluster.executeAsRoot(
            "DROP DATABASE IF EXISTS e11_float",
            "CREATE DATABASE e11_float",
            "CREATE TABLE e11_float.t (id INT NOT NULL, f FLOAT) DUPLICATE KEY(id) " +
                "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')",
            """
            INSERT INTO e11_float.t VALUES
            (1, CAST('Infinity' AS FLOAT)), (2, CAST('-Infinity' AS FLOAT)), (3, CAST('NaN' AS FLOAT)),
            (4, CAST('3.402823e38' AS FLOAT)), (5, CAST('-3.402823e38' AS FLOAT)),
            (6, CAST('0.0' AS FLOAT)), (7, CAST('-0.0' AS FLOAT)), (8, NULL),
            (9, CAST('1.401298464324817e-45' AS FLOAT)),
            (10, CAST('3.4028234663852886e38' AS FLOAT)), (11, CAST('-3.4028234663852886e38' AS FLOAT))
            """.trimIndent(),
        )
        return DorisQueryRunner.builder().build()
    }

    @Test
    fun testConnectorReadsSpecialValuesAndFiniteEdges() {
        val actual = computeActual("SELECT f FROM doris.e11_float.t ORDER BY id").onlyColumn.toList()
        assertThat(actual.map { (it as Float?)?.let(::floatToIntBits) })
            .isEqualTo(expected.map { it?.let(::floatToIntBits) })
    }

    @Test
    fun testSpecialValuePredicatesStayLocalAndCorrect() {
        val infinity = "SELECT id FROM doris.e11_float.t WHERE f = CAST(infinity() AS real)"
        assertThat(query(infinity)).matches("VALUES 1")
        assertThat(query(infinity)).isNotFullyPushedDown(FilterNode::class.java)
        assertThat(query("SELECT id FROM doris.e11_float.t WHERE is_nan(f)")).matches("VALUES 3")
        assertThat(query("SELECT id FROM doris.e11_float.t WHERE f = REAL '0.0'")).matches("VALUES 6, 7")
    }

    @Test
    @Suppress("NestedBlockDepth") // Row-specific assertions with all JDBC resources scoped.
    fun testJdbcGettersRejectInfinitiesWhileTextPreservesThem() {
        DorisTestCluster.openRootConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, f, CAST(f AS DOUBLE) FROM e11_float.t ORDER BY id").use { result ->
                    while (result.next()) {
                        val id = result.getInt(1)
                        val value = expected[id - 1]
                        assertThat(result.getString(2)?.toFloat()?.let(::floatToIntBits)).isEqualTo(value?.let(::floatToIntBits))
                        if (id in 1..2) {
                            assertThatThrownBy { result.getFloat(2) }.isInstanceOf(SQLDataException::class.java)
                            assertThatThrownBy { result.getObject(2) }.isInstanceOf(SQLDataException::class.java)
                        }
                        if (id in 10..11) {
                            // The stored value is the true extremum: widening before transport
                            // reveals precision absent from the ordinary FLOAT wire text.
                            val stored = if (id == 10) Float.MAX_VALUE else -Float.MAX_VALUE
                            assertThat(floatToIntBits(result.getDouble(3).toFloat())).isEqualTo(floatToIntBits(stored))
                            assertThat(floatToIntBits(value!!)).isNotEqualTo(floatToIntBits(stored))
                        }
                    }
                }
            }
        }
    }
}
