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

import com.google.common.util.concurrent.MoreExecutors.directExecutor
import com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.CountDownLatch

/** One execution per scan. Cancellation must not acquire the driver's busy connection lock. */
internal class DorisPreparedStatement(
    private val delegate: PreparedStatement,
    private val connection: Connection,
    private val cancelRemote: (() -> Boolean) -> Unit,
) : PreparedStatement by delegate {
    private val lock = Any()
    private val cancellationComplete = CountDownLatch(1)
    private var started = false
    private var cancelled = false
    private var closed = false

    @Volatile
    private var executionFinished = false

    @Volatile
    private var exhausted = false

    override fun executeQuery(): ResultSet {
        synchronized(lock) {
            if (cancelled || closed || started) {
                throw SQLException("Doris scan statement is cancelled, closed, or already executed")
            }
            started = true
        }
        try {
            val result = delegate.executeQuery()
            if (synchronized(lock) { cancelled }) {
                // JdbcPageSource may already have closed without adopting this late result.
                awaitUninterruptibly(cancellationComplete)
                result.close()
                throw SQLException("Doris scan statement was cancelled during execution")
            }
            return object : ResultSet by result {
                override fun next(): Boolean = result.next().also { if (!it) exhausted = true }
            }
        } finally {
            executionFinished = true
        }
    }

    override fun cancel() {
        val needsAbort = synchronized(lock) {
            if (cancelled) {
                return
            }
            cancelled = true
            started && !exhausted
        }
        try {
            if (needsAbort) {
                try {
                    cancelRemote { executionFinished }
                } finally {
                    // Also covers execute-phase close, where Trino never calls abortReadConnection.
                    // Do not call delegate.cancel(): Connector/J sends an LB-unsafe connection-ID kill.
                    connection.abort(directExecutor())
                }
            }
        } finally {
            cancellationComplete.countDown()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
        }
        try {
            cancel()
        } finally {
            // Another thread may own cancellation. Do not enter draining/locking JDBC close
            // until its socket abort has completed; no driver work runs under our state lock.
            awaitUninterruptibly(cancellationComplete)
            delegate.close()
        }
    }

    override fun isClosed(): Boolean = synchronized(lock) { closed }

    override fun <T : Any?> unwrap(iface: Class<T>): T =
        if (iface.isInstance(this)) iface.cast(this) else delegate.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this) || delegate.isWrapperFor(iface)
}
