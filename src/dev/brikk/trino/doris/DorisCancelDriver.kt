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

import com.mysql.cj.conf.ConnectionUrl
import com.mysql.cj.conf.HostInfo
import com.mysql.cj.conf.HostsListView
import com.mysql.cj.exceptions.CJException
import com.mysql.cj.jdbc.ConnectionImpl
import com.mysql.cj.jdbc.NonRegisteringDriver
import com.mysql.cj.jdbc.exceptions.SQLExceptionsMapping
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Helper-only Connector/J 9.7.0 driver. Any configured FE can perform a cluster-wide kill,
 * so ordinary jdbc:mysql single/failover URLs are tried in order, at most once per list entry,
 * without HA proxies, retries or fallback. Load-balance, replication, DNS SRV and X Protocol
 * URL types are deliberately rejected rather than silently changing their host-selection rules.
 *
 * Limits are imposed AFTER Connector/J merges URL, Properties, host, useConfigs and transform
 * settings. The cached URL and its hosts remain untouched, including normal-driver settings.
 * These are connect/read limits and an attempt budget, NOT an absolute wall-clock deadline:
 * DNS, custom socket/auth plugins and multiple handshake reads can exceed the budget.
 * Query timers are disabled to prevent Connector/J's numeric connection-ID timeout kills;
 * callers must not use Statement.cancel() (which is a separate unsafe kill path).
 */
internal class DorisCancelDriver(
    private val openConnection: (HostInfo) -> Connection = ConnectionImpl::getInstance,
    private val nanoTime: () -> Long = System::nanoTime,
    private val deadlineNanos: Long? = null,
) : NonRegisteringDriver() {
    @Throws(SQLException::class)
    @Suppress("ThrowsCount", "LoopWithTooManyJumpStatements") // JDBC rejection, success and exhausted-host exits.
    override fun connect(url: String?, info: Properties?): Connection? {
        val deadline = deadlineNanos ?: (nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_BUDGET_MILLIS))
        try {
            if (!ConnectionUrl.acceptsUrl(url)) {
                return null
            }
            if (nanoTime() >= deadline) {
                throw SQLException("Doris cancellation helper connection deadline expired", "08001")
            }
            val parsed = ConnectionUrl.getConnectionUrlInstance(url, info)
            if (parsed.type == ConnectionUrl.Type.XDEVAPI_SESSION || parsed.type == ConnectionUrl.Type.XDEVAPI_DNS_SRV_SESSION) {
                throw SQLFeatureNotSupportedException("Doris cancellation helper does not support URL type ${parsed.type}")
            }
            val failure = SQLException("Unable to open a Doris cancellation helper connection", "08001")
            for (host in resolvedHosts(parsed)) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - nanoTime())
                if (remaining <= 0) {
                    break
                }
                val timeout = remaining.coerceIn(1L, NETWORK_TIMEOUT_MILLIS.toLong()).toString()
                val properties = host.hostProperties.toMutableMap().apply {
                    put("connectTimeout", timeout)
                    put("socketTimeout", timeout)
                    put("autoReconnect", "false")
                    put("autoReconnectForPools", "false")
                    put("reconnectAtTxEnd", "false")
                    put("enableQueryTimeouts", "false")
                }
                val boundedHost = HostInfo(parsed, host.host, host.port, host.user, host.password, properties)
                try {
                    return openConnection(boundedHost)
                } catch (e: SQLException) {
                    failure.addSuppressed(e)
                } catch (e: CJException) {
                    failure.addSuppressed(SQLExceptionsMapping.translateException(e))
                }
            }
            throw failure
        } catch (e: CJException) {
            throw SQLExceptionsMapping.translateException(e)
        }
    }

    companion object {
        internal const val NETWORK_TIMEOUT_MILLIS = 2000
        internal const val CONNECT_BUDGET_MILLIS = 15000L

        internal fun resolvedHosts(parsed: ConnectionUrl): List<HostInfo> =
            if (parsed.type == ConnectionUrl.Type.REPLICATION_DNS_SRV_CONNECTION) {
                // The ALL view contains unresolved SRV service names with port -1.
                parsed.getHostsList(HostsListView.SOURCES) + parsed.getHostsList(HostsListView.REPLICAS)
            } else {
                parsed.hostsList
            }
    }
}
