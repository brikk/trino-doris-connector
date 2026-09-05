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

import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.spi.statistics.ColumnStatistics
import io.trino.spi.statistics.DoubleRange
import io.trino.spi.statistics.TableStatistics
import io.trino.spi.type.BigintType.BIGINT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

class TestDorisTableStatisticsReader {
    private val column = JdbcColumnHandle("v", DorisTypeMapping.toTypeHandle("bigint"), BIGINT)
    private val base = mapOf(
        "column_name" to "v", "index_name" to "BaseTable", "count" to "5.0", "ndv" to "5.0",
        "num_null" to "0.0", "data_size" to "40.0", "min" to "5", "max" to "15",
    )

    @Test
    fun testBaseIndexWinsRegardlessOfOrder() {
        val rollup = base + mapOf("index_name" to "rollup", "count" to "3.0", "ndv" to "3.0", "data_size" to "24.0", "max" to "28")
        val largerIndex = base + mapOf("index_name" to "another_index", "count" to "1000.0", "max" to "1000")
        for (rows in listOf(listOf(rollup, largerIndex, base), listOf(base, largerIndex, rollup))) {
            val stats = read(0, rows)
            assertThat(stats.rowCount.value).isEqualTo(5.0)
            val value = stats.columnStatistics.getValue(column)
            assertThat(value.distinctValuesCount.value).isEqualTo(5.0)
            assertThat(value.dataSize.value).isEqualTo(40.0)
            assertThat(value.range).contains(DoubleRange(5.0, 15.0))
        }
    }

    @Test
    fun testMissingBaseIndexDoesNotFallBackToRollupOrUnnamedRows() {
        val rows = listOf(base + ("index_name" to "rollup"), base + ("index_name" to null))
        val stats = read(7, rows)
        assertThat(stats.rowCount.value).isEqualTo(7.0)
        assertThat(stats.columnStatistics).isEmpty()
        assertThat(read(0, rows).rowCount.isUnknown).isTrue()
    }

    @Test
    fun testStaleEmptyColumnIsDiscardedWithFreshTableOrOtherColumnCounts() {
        val stale = base + mapOf("count" to "0.0", "ndv" to "0.0", "data_size" to "0.0", "min" to "N/A", "max" to "N/A")
        val fresh = base + mapOf("column_name" to "id", "count" to "2.0", "ndv" to "2.0")
        for (stats in listOf(read(2, listOf(stale)), read(0, listOf(stale, fresh)))) {
            assertThat(stats.rowCount.value).isEqualTo(2.0)
            assertUnknownColumn(stats)
        }
        assertThat(read(0, listOf(stale)).rowCount.isUnknown).isTrue()
    }

    @Test
    fun testZeroNdvWithNonNullOrUnknownNullCountIsDiscarded() {
        for (nulls in listOf("0.0", "2.0", null)) {
            // Discard stale size/range/null estimates too, not just the contradictory NDV.
            val stats = read(5, listOf(base + mapOf("ndv" to "0.0", "num_null" to nulls)))
            assertThat(stats.rowCount.value).isEqualTo(5.0)
            assertUnknownColumn(stats)
        }
        // Previously all-NULL statistics may also become inconsistent after new rows arrive.
        assertUnknownColumn(read(6, listOf(base + mapOf("ndv" to "0.0", "num_null" to "5.0"))))
    }

    @Test
    fun testLegitimateAllNullStatisticsArePreserved() {
        val stats = read(0, listOf(base + mapOf("ndv" to "0.0", "num_null" to "5.0", "min" to "N/A", "max" to "N/A")))
        assertThat(stats.rowCount.value).isEqualTo(5.0)
        val value = stats.columnStatistics.getValue(column)
        assertThat(value.distinctValuesCount.value).isZero()
        assertThat(value.nullsFraction.value).isEqualTo(1.0)
        assertThat(value.dataSize.value).isEqualTo(40.0)
        assertThat(value.range).isEmpty()
    }

    @Test
    fun testPositiveNdvStillCapsAtNonNullRows() {
        val stats = read(5, listOf(base + mapOf("ndv" to "9.0", "num_null" to "2.0")))
        val value = stats.columnStatistics.getValue(column)
        assertThat(value.distinctValuesCount.value).isEqualTo(3.0)
        assertThat(value.nullsFraction.value).isEqualTo(0.4)
    }

    private fun assertUnknownColumn(stats: TableStatistics) {
        val value = stats.columnStatistics[column] ?: ColumnStatistics.empty()
        assertThat(value.distinctValuesCount.isUnknown).isTrue()
        assertThat(value.nullsFraction.isUnknown).isTrue()
        assertThat(value.dataSize.isUnknown).isTrue()
        assertThat(value.range).isEmpty()
    }

    @Suppress("CyclomaticComplexMethod") // Strict fake JDBC responses for both metadata queries.
    private fun read(tableRows: Long, rows: List<Map<String, String?>>): TableStatistics {
        val tableResult = proxy(ResultSet::class.java) { method, _ ->
            when (method) {
                "next" -> true
                "getLong" -> tableRows
                "wasNull" -> false
                "close" -> null
                else -> error("Unexpected table-row result call: $method")
            }
        }
        val tableStatement = proxy(PreparedStatement::class.java) { method, _ ->
            when (method) {
                "executeQuery" -> tableResult
                "setString", "close" -> null
                else -> error("Unexpected table-row statement call: $method")
            }
        }
        var row = -1
        val columnResult = proxy(ResultSet::class.java) { method, args ->
            when (method) {
                "next" -> ++row < rows.size
                "getString" -> rows[row][args[0]]
                "close" -> null
                else -> error("Unexpected column-statistics result call: $method")
            }
        }
        val columnStatement = proxy(Statement::class.java) { method, args ->
            when (method) {
                "executeQuery" -> {
                    assertThat(args[0]).isEqualTo("SHOW COLUMN CACHED STATS `schema`.`BaseTable`")
                    columnResult
                }
                "close" -> null
                else -> error("Unexpected column-statistics statement call: $method")
            }
        }
        val connection = proxy(Connection::class.java) { method, _ ->
            when (method) {
                "prepareStatement" -> tableStatement
                "createStatement" -> columnStatement
                "close" -> null
                else -> error("Unexpected connection call: $method")
            }
        }
        return connection.use { DorisTableStatisticsReader { "`$it`" }.readStatistics(it, "schema", "BaseTable", listOf(column)) }
    }

    private fun <T> proxy(type: Class<T>, handler: (String, Array<out Any?>) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> handler(method.name, args ?: emptyArray()) })
}
