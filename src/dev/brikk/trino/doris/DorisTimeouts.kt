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

import io.airlift.units.Duration
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** One range/normalization contract for catalog, session, connection and scan paths. */
internal object DorisTimeouts {
    fun connectMillis(timeout: Duration): Int = normalized(timeout, TimeUnit.MILLISECONDS, "doris.connect-timeout", "ms")

    fun querySeconds(timeout: Duration): Int = normalized(timeout, TimeUnit.SECONDS, "doris.query-timeout/query_timeout", "s")

    private fun normalized(timeout: Duration, unit: TimeUnit, property: String, suffix: String): Int {
        val value = timeout.getValue(unit)
        require(value.isFinite() && value >= 1.0 && value <= Int.MAX_VALUE.toDouble()) {
            "$property must be between 1$suffix and ${Int.MAX_VALUE}$suffix (inclusive); got $timeout"
        }
        // Never shorten a valid requested budget or round it to the driver's unbounded zero.
        return ceil(value).toInt()
    }
}
