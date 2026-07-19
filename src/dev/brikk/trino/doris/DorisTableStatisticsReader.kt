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

/**
 * Doris 4.1.3 statistics reader (PLAN P4 tail "remote statistics"; SR K12 pattern with the
 * SQL re-derived against live Doris — probe verdicts in `dev-docs/NOTES-p5-statistics.md`).
 *
 * Sources (all READ-only; the connector NEVER issues ANALYZE — Doris auto-analyze is on by
 * default and users manage manual ANALYZE themselves):
 * - Row count: `information_schema.tables.TABLE_ROWS` — tablet-report-fed, EXACT for OLAP
 *   tables once the report lands (~1 report interval of staleness after writes; `-1` for
 *   views), populated WITHOUT any ANALYZE.
 * - Column stats: `SHOW COLUMN CACHED STATS` — served from the FE stats cache, populated by
 *   auto/manual ANALYZE; per column: count / ndv / num_null / data_size / min / max.
 *
 * Fidelity guards (probe-proven):
 * - `count` from column stats is exact at analyze time and FRESHER than TABLE_ROWS right
 *   after a load (TABLE_ROWS lags one tablet report, reading 0 on a just-loaded table) —
 *   the row-count estimate takes the MAX of the two sources.
 * - `ndv` is a sketch and can OVERSHOOT the row count (probed: 1006 distinct over 1000
 *   rows) — capped at the non-null row count.
 * - min/max RANGES are fed to the optimizer only for numeric families whose Doris text is
 *   the plain numeric value (integers, LARGEINT, DECIMAL, FLOAT/DOUBLE) and only when both
 *   ends parse FINITE — the probe showed `NaN` / `∞` / quoted text renderings for
 *   float/double extremes and temporal/string/IP columns, none of which may reach Trino as
 *   exact values. Temporal min/max are deliberately skipped (their stats-representation
 *   contract is unproven); ndv/nulls are still supplied for every analyzed column.
 */
internal class DorisTableStatisticsReader(
    private val quote: (String) -> String,
) {
    /** One `SHOW COLUMN CACHED STATS` row, text fields verbatim. */
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

        val statsRowCount = columnStats.values.mapNotNull { it.count }.maxOrNull()
        val rowCount = listOfNotNull(tableRows?.toDouble(), statsRowCount).maxOrNull()
        if (rowCount == null || rowCount <= 0) {
            // 0 is indistinguishable from tablet-report lag on a just-loaded table (probed:
            // TABLE_ROWS reads 0 for ~1 report interval after INSERT) — feeding an exact 0
            // would mislead the optimizer far worse than unknown, so both degrade to unknown.
            return TableStatistics.empty()
        }

        val builder = TableStatistics.builder().setRowCount(Estimate.of(rowCount))
        for (column in columns) {
            val stats = columnStats[column.columnName] ?: continue
            builder.setColumnStatistics(column, toColumnStatistics(column, stats, rowCount))
        }
        return builder.build()
    }

    /** `TABLE_ROWS` (tablet-report-fed); null when absent or the `-1` view marker. */
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

    /** `SHOW COLUMN CACHED STATS` keyed by column name; empty when never analyzed (or a view). */
    private fun readColumnStats(connection: Connection, remoteSchema: String, remoteTable: String): Map<String, ColumnStatsRow> {
        val byColumn = LinkedHashMap<String, ColumnStatsRow>()
        val sql = "SHOW COLUMN CACHED STATS ${quote(remoteSchema)}.${quote(remoteTable)}"
        runCatching {
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { resultSet ->
                    while (resultSet.next()) {
                        val name = resultSet.getString("column_name") ?: continue
                        // first row per column wins (extra rows only appear for extra
                        // materialized indexes; the base index is reported first)
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
            }
        }.onFailure { e ->
            // e.g. views — column stats simply unavailable; row count may still be usable
            log.debug("SHOW COLUMN CACHED STATS failed for %s.%s: %s", remoteSchema, remoteTable, e.message)
        }
        return byColumn
    }

    private fun toColumnStatistics(column: JdbcColumnHandle, stats: ColumnStatsRow, rowCount: Double): ColumnStatistics {
        val builder = ColumnStatistics.builder()
        val nullsFraction = stats.numNull?.let { (it / rowCount).coerceIn(0.0, 1.0) }
        if (nullsFraction != null) {
            builder.setNullsFraction(Estimate.of(nullsFraction))
        }
        if (stats.ndv != null) {
            // the NDV sketch can overshoot the row count (probe: 1006 over 1000 rows)
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
     * min/max range for proven-plain-numeric families only; anything unparseable or
     * non-finite (`NaN`, `∞`, quoted temporals/strings/IPs) yields no range.
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
        // BigDecimal accepts every plain numeric rendering Doris emits for the allowed
        // families (incl. LARGEINT/DECIMAL(76) extremes) and REJECTS NaN/Infinity/∞/quoted
        // text — exactly the fail-closed contract we want.
        val value = runCatching { BigDecimal(text).toDouble() }.getOrNull() ?: return null
        return if (value.isFinite()) value else null
    }

    companion object {
        private val log = Logger.get(DorisTableStatisticsReader::class.java)

        private const val TABLE_ROWS_QUERY =
            "SELECT TABLE_ROWS FROM information_schema.tables WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?"
    }
}
