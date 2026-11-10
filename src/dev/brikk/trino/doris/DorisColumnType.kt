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
import io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR
import io.trino.spi.TrinoException
import io.trino.spi.type.VarcharType
import java.util.Locale

/**
 * A parsed `information_schema.columns.COLUMN_TYPE`, such as `tinyint(1)` (BOOLEAN), `int(11)`,
 * `decimalv3(38, 10)`, `datetime(6)`, `string`, `array<int(11)>`, `struct<int(11),string>` or
 * `unknown` (AGG_STATE).
 */
internal data class DorisColumnType(
    val baseName: String,
    val arguments: List<Int>,
    val raw: String,
) {
    companion object {
        fun parse(columnType: String): DorisColumnType {
            val raw = columnType.trim()
            val lower = raw.lowercase(Locale.ENGLISH)
            val baseEnd = lower.indexOfFirst { it == '(' || it == '<' }.let { if (it < 0) lower.length else it }
            val baseName = lower.substring(0, baseEnd).trim()
            if (baseName.isEmpty()) {
                throw TrinoException(GENERIC_INTERNAL_ERROR, "Malformed Doris COLUMN_TYPE: '$columnType'")
            }
            // Only scalar types carry integer arguments. Complex types are identified by base
            // name; the parentheses nested inside them are not parsed here.
            val arguments = if (baseEnd < lower.length && lower[baseEnd] == '(') {
                parseArguments(lower, baseEnd, columnType)
            } else {
                emptyList()
            }
            return DorisColumnType(baseName, arguments, raw)
        }

        /**
         * True for a VARCHAR column backed by a Doris string type. Decimal256 and complex types
         * rendered as text are also VARCHAR in Trino but do not compare or aggregate as strings.
         */
        fun isNativeString(column: JdbcColumnHandle): Boolean =
            column.columnType is VarcharType && column.jdbcTypeHandle.jdbcTypeName()
                .map { parse(it).baseName in NATIVE_STRING_TYPES }
                .orElse(false)

        private val NATIVE_STRING_TYPES = setOf("varchar", "string", "text")

        private fun parseArguments(lower: String, openParen: Int, columnType: String): List<Int> {
            val close = lower.indexOf(')', openParen)
            if (close < 0) {
                throw TrinoException(GENERIC_INTERNAL_ERROR, "Malformed Doris COLUMN_TYPE (unterminated '('): '$columnType'")
            }
            return lower.substring(openParen + 1, close)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map {
                    it.toIntOrNull()
                        ?: throw TrinoException(GENERIC_INTERNAL_ERROR, "Malformed Doris COLUMN_TYPE argument '$it' in '$columnType'")
                }
        }
    }
}
