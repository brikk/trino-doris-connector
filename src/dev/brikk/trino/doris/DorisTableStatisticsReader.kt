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

import io.airlift.log.Logger
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.spi.statistics.ColumnStatistics
import io.trino.spi.statistics.DoubleRange
import io.trino.spi.statistics.Estimate
import io.trino.spi.statistics.TableStatistics
import io.trino.spi.type.BigintType
import io.trino.spi.type.DecimalType
import io.trino.spi.type.DoubleType
import io.trino.spi.type.IntegerType
import io.trino.spi.type.RealType
import io.trino.spi.type.SmallintType
import io.trino.spi.type.TinyintType
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

/**
 * Row counts come from `information_schema.tables.TABLE_ROWS`, column statistics from
 * `SHOW COLUMN CACHED STATS`. The connector never runs ANALYZE, which writes to Doris; column
 * statistics exist only after Doris auto-analyze or a user's ANALYZE.
 */
internal class DorisTableStatisticsReader(
    private val quote: (String) -> String,
) {
    private data class ColumnStatsRow(
        val count: Double?,
        val ndv: Double?,
        val numNull: Double?,
        val dataSize: Double?,
        val min: String?,
        val max: String?,
    )

    fun readStatistics(
        connection: Connection,
        remoteSchema: String,
        remoteTable: String,
        columns: List<JdbcColumnHandle>,
    ): TableStatistics {
        val tableRows = readTableRows(connection, remoteSchema, remoteTable)
        val columnStats = readColumnStats(connection, remoteSchema, remoteTable)

        // TABLE_ROWS lags a tablet report behind loads, so the larger of the two counts is used.
        val statsRowCount = columnStats.values.mapNotNull { it.count }.maxOrNull()
        val rowCount = listOfNotNull(tableRows?.toDouble(), statsRowCount).maxOrNull()
        if (rowCount == null || rowCount <= 0) {
            // TABLE_ROWS reads 0 until the first tablet report after a load; an exact 0 would
            // mislead the optimizer more than an unknown count.
            return TableStatistics.empty()
        }

        val builder = TableStatistics.builder().setRowCount(Estimate.of(rowCount))
        for (column in columns) {
            val stats = columnStats[column.columnName] ?: continue
            builder.setColumnStatistics(column, toColumnStatistics(column, stats, rowCount))
        }
        return builder.build()
    }

    /** Null when absent or `-1`, which Doris reports for views. */
    private fun readTableRows(connection: Connection, remoteSchema: String, remoteTable: String): Long? {
        connection.prepareStatement(TABLE_ROWS_QUERY).use { statement ->
            statement.setString(1, remoteSchema)
            statement.setString(2, remoteTable)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    return null
                }
                val rows = resultSet.getLong(1)
                return if (resultSet.wasNull() || rows < 0) null else rows
            }
        }
    }

    private fun readColumnStats(connection: Connection, remoteSchema: String, remoteTable: String): Map<String, ColumnStatsRow> {
        val byColumn = LinkedHashMap<String, ColumnStatsRow>()
        val sql = "SHOW COLUMN CACHED STATS ${quote(remoteSchema)}.${quote(remoteTable)}"
        try {
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet -> collectBaseIndexStats(resultSet, remoteTable, byColumn) }
            }
        } catch (e: SQLException) {
            // e.g. a view; the row count may still be usable
            log.debug("SHOW COLUMN CACHED STATS failed for %s.%s: %s", remoteSchema, remoteTable, e.message)
        }
        return byColumn
    }

    private fun collectBaseIndexStats(resultSet: ResultSet, remoteTable: String, byColumn: MutableMap<String, ColumnStatsRow>) {
        while (resultSet.next()) {
            // SHOW emits separate visible-index rows in unspecified order. The
            // base index name is the remote table name, including after rename.
            val name = resultSet.getString("column_name")
            if (name == null || resultSet.getString("index_name") != remoteTable) {
                continue
            }
            byColumn.putIfAbsent(
                name,
                ColumnStatsRow(
                    count = resultSet.getString("count")?.toDoubleOrNull(),
                    ndv = resultSet.getString("ndv")?.toDoubleOrNull(),
                    numNull = resultSet.getString("num_null")?.toDoubleOrNull(),
                    dataSize = resultSet.getString("data_size")?.toDoubleOrNull(),
                    min = resultSet.getString("min"),
                    max = resultSet.getString("max"),
                ),
            )
        }
    }

    private fun toColumnStatistics(column: JdbcColumnHandle, stats: ColumnStatsRow, rowCount: Double): ColumnStatistics {
        // Empty-table statistics can survive a load or partial ANALYZE. NDV zero is
        // only consistent with a nonempty table when every row is known to be NULL.
        if (stats.count == 0.0 || (stats.ndv == 0.0 && stats.numNull != rowCount)) {
            log.debug("Ignoring inconsistent zero-count/NDV statistics for %s", column.columnName)
            return ColumnStatistics.empty()
        }
        val builder = ColumnStatistics.builder()
        val nullsFraction = stats.numNull?.let { (it / rowCount).coerceIn(0.0, 1.0) }
        if (nullsFraction != null) {
            builder.setNullsFraction(Estimate.of(nullsFraction))
        }
        if (stats.ndv != null) {
            // the NDV sketch can exceed the row count
            val nonNullRows = rowCount * (1.0 - (nullsFraction ?: 0.0))
            builder.setDistinctValuesCount(Estimate.of(stats.ndv.coerceAtMost(nonNullRows)))
        }
        if (stats.dataSize != null && stats.dataSize >= 0) {
            builder.setDataSize(Estimate.of(stats.dataSize))
        }
        numericRange(column, stats)?.let { builder.setRange(it) }
        return builder.build()
    }

    /**
     * Only numeric types get a range: Doris renders temporal, string and IP min/max as quoted
     * text, and float extremes as `NaN` or `∞`, none of which can be fed to Trino as values.
     */
    private fun numericRange(column: JdbcColumnHandle, stats: ColumnStatsRow): DoubleRange? {
        when (column.columnType) {
            is TinyintType, is SmallintType, is IntegerType, is BigintType,
            is RealType, is DoubleType, is DecimalType,
            -> Unit
            else -> return null
        }
        val min = parseFinite(stats.min) ?: return null
        val max = parseFinite(stats.max) ?: return null
        if (min > max) {
            return null
        }
        return DoubleRange(min, max)
    }

    private fun parseFinite(text: String?): Double? {
        if (text.isNullOrEmpty() || text.equals("null", ignoreCase = true)) {
            return null
        }
        // BigDecimal accepts every plain number Doris prints, including LARGEINT and DECIMAL(76)
        // extremes, and rejects NaN, Infinity, ∞ and quoted text.
        val value = try {
            BigDecimal(text).toDouble()
        } catch (e: NumberFormatException) {
            return null
        }
        return if (value.isFinite()) value else null
    }

    companion object {
        private val log = Logger.get(DorisTableStatisticsReader::class.java)

        private const val TABLE_ROWS_QUERY =
            "SELECT TABLE_ROWS FROM information_schema.tables WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"
    }
}
