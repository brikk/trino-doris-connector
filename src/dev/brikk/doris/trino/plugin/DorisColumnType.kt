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
package dev.brikk.doris.trino.plugin

import io.trino.spi.TrinoException
import io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR
import java.util.Locale

/**
 * Parsed form of a Doris `information_schema.columns.COLUMN_TYPE` string — the single
 * authoritative type source for this connector (ledger global rule 2: `getColumns` is
 * proven lossy, PROBE §1/Impl #1).
 *
 * Observed 4.1.3 examples: `tinyint(1)` (boolean), `tinyint(4)`, `int(11)`, `largeint`,
 * `decimalv3(38, 10)`, `datetime(6)`, `varchar(100)`, `string`, `array<int(11)>`,
 * `map<string,int(11)>`, `struct<int(11),string>`, `bitmap`, `hll`, `unknown` (agg_state).
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
            // Parenthesized integer arguments apply only to scalar types (decimalv3(38, 10),
            // varchar(100), datetime(6), tinyint(1), ...). Complex types (array<...>, map<...>,
            // struct<...>) are dispatched on base name alone in P1a; their nested parens
            // (e.g. struct<int(11),string>) are intentionally not parsed here.
            val arguments = if (baseEnd < lower.length && lower[baseEnd] == '(') {
                parseArguments(lower, baseEnd, columnType)
            } else {
                emptyList()
            }
            return DorisColumnType(baseName, arguments, raw)
        }

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
