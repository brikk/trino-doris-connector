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

import com.mysql.cj.NativeSession
import com.mysql.cj.jdbc.ConnectionImpl
import com.mysql.cj.jdbc.JdbcConnection
import com.mysql.cj.jdbc.NonRegisteringDriver
import java.sql.Connection
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.Executor

/**
 * Connector/J 26.7.0 performs ordinary URL host selection at connect time. Thereafter a
 * connector read connection is one physical attempt, not a mutable HA routing proxy.
 * Trino owns retries; no statement/result may silently migrate after execution begins.
 * This runs below DriverConnectionFactory's telemetry wrappers, preserving instrumentation.
 */
internal class DorisReadDriver : NonRegisteringDriver() {
    override fun connect(url: String?, info: Properties?): Connection? =
        super.connect(url, info)?.let(::pin)

    companion object {
        /** Resolve only while idle, before any caller can execute a statement. */
        internal fun pin(owner: Connection): Connection {
            try {
                val selected = physicalConnection(owner)
                // StatementImpl otherwise records a MultiHostMySQLConnection: its
                // getSession() follows later routing changes, even after unwrapping
                // the statement. Detach BEFORE preparing/binding any statement.
                selected.setProxy(null)
                if (selected.multiHostSafeProxy !== selected || selected.connectionLock !== selected.lock) {
                    throw SQLException("Cannot pin Doris reads outside the HA routing lock")
                }
                return PinnedReadConnection(selected, owner)
            } catch (failure: SQLException) {
                runCatching { owner.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }

        private fun physicalConnection(owner: Connection): JdbcConnection {
            var selected = owner.unwrap(JdbcConnection::class.java)
            repeat(8) {
                if (selected is ConnectionImpl) {
                    if (selected.session !is NativeSession) throw SQLException("Unsupported Doris read session")
                    return selected
                }
                // Replication can nest load-balancing proxies; unwrap each returned
                // JDBC interface before following its next active connection.
                selected = selected.activeMySQLConnection.unwrap(JdbcConnection::class.java)
            }
            throw SQLException("Cannot resolve a physical Doris read connection")
        }
    }
}

/** Keep the original HA owner alive for complete pool cleanup, never for read/abort calls. */
private class PinnedReadConnection(
    private val physical: JdbcConnection,
    private val owner: Connection,
) : Connection by physical {
    override fun abort(executor: Executor) = physical.abort(executor)

    override fun close() = owner.close()

    override fun <T : Any?> unwrap(iface: Class<T>): T =
        if (iface.isInstance(this)) iface.cast(this) else physical.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this) || physical.isWrapperFor(iface)
}
