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

import io.airlift.configuration.Config
import io.airlift.configuration.ConfigDescription
import io.airlift.units.DataSize
import io.airlift.units.Duration
import java.util.concurrent.TimeUnit

/**
 * Doris-specific connector settings. The three Doris session variables are applied at
 * SESSION scope only — never globally — via the Connector/J `sessionVariables` URL
 * property at connection open (PROBE §9; ledger §B "session vars").
 */
class DorisConfig {
    private var queryTimeout: Duration? = null
    private var execMemLimit: DataSize? = null
    private var timeZone: String? = null
    private var connectTimeout: Duration = Duration(10.0, TimeUnit.SECONDS)
    private var stringPushdownMode: DorisStringPushdownMode = DorisStringPushdownMode.GUARDED
    private var statisticsEnabled: Boolean = true
    private var clusterScopedCancel: Boolean = true
    private var approximatePushdown: Boolean = false

    fun getQueryTimeout(): Duration? = queryTimeout

    @Config("doris.query-timeout")
    @ConfigDescription("Doris session variable query_timeout (seconds granularity), applied per connection session")
    fun setQueryTimeout(queryTimeout: Duration?): DorisConfig {
        this.queryTimeout = queryTimeout
        return this
    }

    fun getExecMemLimit(): DataSize? = execMemLimit

    @Config("doris.exec-mem-limit")
    @ConfigDescription("Doris session variable exec_mem_limit (bytes), applied per connection session")
    fun setExecMemLimit(execMemLimit: DataSize?): DorisConfig {
        this.execMemLimit = execMemLimit
        return this
    }

    fun getTimeZone(): String? = timeZone

    @Config("doris.time-zone")
    @ConfigDescription("Doris session variable time_zone (e.g. Etc/UTC), applied per connection session")
    fun setTimeZone(timeZone: String?): DorisConfig {
        this.timeZone = timeZone
        return this
    }

    fun getStringPushdownMode(): DorisStringPushdownMode = stringPushdownMode

    @Config("doris.string-pushdown.mode")
    @ConfigDescription(
        "String predicate pushdown mode: NULL_ONLY, GUARDED (superset pre-filter, Trino filter retained; default), " +
            "BINARY (verified byte semantics), FULL (caller-asserted). Per-query override: string_pushdown_mode",
    )
    fun setStringPushdownMode(stringPushdownMode: DorisStringPushdownMode): DorisConfig {
        this.stringPushdownMode = stringPushdownMode
        return this
    }

    fun isStatisticsEnabled(): Boolean = statisticsEnabled

    @Config("doris.statistics.enabled")
    @ConfigDescription(
        "Expose Doris table/column statistics (tablet-report row counts + ANALYZE/auto-analyze column stats) " +
            "to the Trino optimizer. Read-only; the connector never issues ANALYZE. " +
            "Per-query override: statistics_enabled",
    )
    fun setStatisticsEnabled(statisticsEnabled: Boolean): DorisConfig {
        this.statisticsEnabled = statisticsEnabled
        return this
    }

    fun isApproximatePushdown(): Boolean = approximatePushdown

    @Config("doris.approximate-pushdown")
    @ConfigDescription(
        "Push approximate aggregates (approx_distinct -> approx_count_distinct) to Doris. " +
            "OFF by default: both engines return HyperLogLog ESTIMATES but with different " +
            "sketches, so pushed and local answers legitimately differ for the same data.",
    )
    fun setApproximatePushdown(approximatePushdown: Boolean): DorisConfig {
        this.approximatePushdown = approximatePushdown
        return this
    }

    fun isClusterScopedCancel(): Boolean = clusterScopedCancel

    @Config("doris.cluster-scoped-cancel")
    @ConfigDescription(
        "Cancel queries cluster-wide: an async helper connection resolves the running query's " +
            "QueryId from the cluster-wide processlist and issues KILL QUERY — correct behind " +
            "load balancers / multi-FE where the driver-level kill targets an arbitrary FE. " +
            "The stock socket abort is retained as a fallback either way.",
    )
    fun setClusterScopedCancel(clusterScopedCancel: Boolean): DorisConfig {
        this.clusterScopedCancel = clusterScopedCancel
        return this
    }

    fun getConnectTimeout(): Duration = connectTimeout

    @Config("doris.connect-timeout")
    @ConfigDescription("Connector/J connectTimeout for connections to the Doris FE")
    fun setConnectTimeout(connectTimeout: Duration): DorisConfig {
        this.connectTimeout = connectTimeout
        return this
    }
}
