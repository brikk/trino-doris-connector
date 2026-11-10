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
import io.airlift.log.Logger
import io.trino.spi.connector.ConnectorSession
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kills a scan's query on whichever FE runs it. Connector/J's `Statement.cancel()` sends
 * `KILL QUERY <connection-id>` over a new connection; behind a load balancer that can reach
 * another FE, where connection ids mean something else, and do nothing or kill an unrelated
 * session. A socket abort alone frees the query only if the owning FE notices promptly.
 *
 * Each scan statement starts with a unique `/*trino_statement_id=<uuid>*/` marker. A helper
 * connection to any FE finds the marker in `SHOW FULL PROCESSLIST` and issues
 * `KILL QUERY "<QueryId>"`, which Doris forwards to the owning FE. Killing by trace id is not
 * used because some Doris versions accept it and do nothing.
 *
 * The helper runs asynchronously and never touches the scan connection, where even
 * `getClientInfo` can block. Every failure falls back to the socket abort in
 * [DorisPreparedStatement], so cancellation never hangs on the helper.
 *
 * Adapted from the Apache-2.0 implementation in dev.sort.doris.cancel.
 */
internal class DorisClusterScopedCancel(
    private val connectionFactory: DorisCancelConnectionFactory,
    private val enabled: Boolean,
    private val executor: Executor = SHARED_EXECUTOR,
    private val timeoutMillis: Long = KILL_WINDOW_MILLIS,
    private val retryScheduler: ScheduledExecutorService = RETRY_SCHEDULER,
    private val requestSlots: Semaphore = SHARED_REQUEST_SLOTS,
    private val watchdogScheduler: ScheduledExecutorService = WATCHDOG,
) {
    private val visibilityWarningLogged = AtomicBoolean()
    internal val stats = Stats()

    internal class Stats {
        val killsDispatched = AtomicInteger()
        val killsIssued = AtomicInteger()
        val killsRejected = AtomicInteger()
        val killsExpired = AtomicInteger()
        val helperTimeouts = AtomicInteger()
    }

    init {
        require(timeoutMillis > 0) { "Cancellation timeout must be positive" }
    }

    /**
     * [executionFinished] must report that the statement's execute call has returned, not merely
     * that the scan was cancelled or closed: only then does an empty processlist prove release.
     */
    fun cancel(session: ConnectorSession, marker: String, executionFinished: () -> Boolean) {
        if (!enabled) {
            return
        }
        // Counts running, queued and delayed requests across all catalogs in this classloader.
        if (!requestSlots.tryAcquire()) {
            stats.killsRejected.incrementAndGet()
            log.debug("cluster-scoped cancel pending-request limit reached for %s", marker)
            return
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        if (Request(session, marker, executionFinished, deadline).dispatch()) {
            stats.killsDispatched.incrementAndGet()
        }
    }

    private inner class Request(
        private val session: ConnectorSession,
        private val marker: String,
        private val executionFinished: () -> Boolean,
        private val deadline: Long,
    ) : Runnable {
        private val completed = AtomicBoolean()
        private var observed = false
        private var finishedEmptyProbes = 0

        fun dispatch(): Boolean {
            if (completed.get()) {
                return false
            }
            if (System.nanoTime() >= deadline) {
                finish(expired = true)
                return false
            }
            return try {
                executor.execute(this)
                true
            } catch (failure: RejectedExecutionException) {
                reject(failure)
                false
            }
        }

        override fun run() {
            if (completed.get()) {
                return
            }
            if (System.nanoTime() >= deadline) {
                finish(expired = true)
                return
            }
            var retry = false
            try {
                retry = probeQuietly(session, marker, deadline) { statement ->
                    // Read before scanning: a query that finishes during SHOW does not prove absence.
                    val finishedBeforeScan = executionFinished()
                    val queries = scanRunningQueries(statement, marker)
                    if (isGone(!queries.found, finishedBeforeScan)) {
                        false
                    } else {
                        observed = observed || queries.found
                        for (queryId in queries.queryIds) {
                            if (System.nanoTime() >= deadline) {
                                break
                            }
                            killQueryId(statement, queryId, marker)
                        }
                        true
                    }
                }
            } finally {
                if (!retry) {
                    finish() // also releases the request slot after an unchecked failure
                }
            }
            if (retry) {
                scheduleRetry()
            }
        }

        private fun isGone(emptyScan: Boolean, finishedBeforeScan: Boolean): Boolean {
            if (!emptyScan || !finishedBeforeScan) {
                finishedEmptyProbes = 0
                return false
            }
            finishedEmptyProbes++
            return observed || finishedEmptyProbes >= FINISHED_EMPTY_PROBE_LIMIT
        }

        private fun scheduleRetry() {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                finish(expired = true)
                return
            }
            try {
                retryScheduler.schedule(
                    { dispatch() },
                    minOf(TimeUnit.MILLISECONDS.toNanos(KILL_RECHECK_MILLIS), remaining),
                    TimeUnit.NANOSECONDS,
                )
            } catch (failure: RejectedExecutionException) {
                reject(failure)
            }
        }

        private fun reject(failure: RejectedExecutionException) {
            stats.killsRejected.incrementAndGet()
            log.debug(failure, "cluster-scoped cancel dispatch failed for %s", marker)
            finish()
        }

        private fun finish(expired: Boolean = false) {
            if (completed.compareAndSet(false, true)) {
                if (expired) {
                    stats.killsExpired.incrementAndGet()
                    log.debug("cluster-scoped cancel expired before release was verified for %s", marker)
                }
                requestSlots.release()
            }
        }
    }

    /**
     * One probe on a fresh helper connection. Requests probe again until the row is gone, because
     * a kill issued in the first instants of a query, before it can be cancelled, is accepted by
     * Doris and does nothing. Killing a query that is already dying is harmless.
     */
    @Suppress("TooGenericExceptionCaught") // best-effort: the socket abort runs regardless
    private fun probeQuietly(session: ConnectorSession, marker: String, deadline: Long, probe: (Statement) -> Boolean): Boolean {
        var watchdog: ScheduledFuture<*>? = null
        try {
            return connectionFactory.openConnection(session, deadline).use { helper ->
                // Keep the watchdog armed through resource close too, which can perform network I/O.
                watchdog = watchdogScheduler.schedule(
                    { abortHelper(helper, marker) },
                    maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS,
                )
                if (System.nanoTime() >= deadline) {
                    return@use false
                }
                // Connector/J implements this as SO_TIMEOUT, not a numeric-ID KILL timer.
                helper.setNetworkTimeout(directExecutor(), DorisCancelDriver.NETWORK_TIMEOUT_MILLIS)
                helper.createStatement().use { statement ->
                    enableClusterVisibility(statement, marker)
                    probe(statement)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.debug(e, "cluster-scoped cancel helper interrupted for %s", marker)
        } catch (e: Exception) {
            log.debug(e, "cluster-scoped cancel helper failed for %s", marker)
        } finally {
            watchdog?.cancel(false)
        }
        return false
    }

    private fun enableClusterVisibility(statement: Statement, marker: String) {
        try {
            statement.execute("SET fetch_all_fe_for_system_table = true")
        } catch (e: SQLException) {
            if (visibilityWarningLogged.compareAndSet(false, true)) {
                log.warn(
                    e,
                    "Could not enable fetch_all_fe_for_system_table for %s; cluster-wide visibility is not guaranteed. " +
                        "Continuing best-effort cancellation with socket-abort fallback (warning once per connector).",
                    marker,
                )
            }
        }
    }

    private class RunningQueries(val found: Boolean, val queryIds: List<String>)

    private fun scanRunningQueries(statement: Statement, marker: String): RunningQueries =
        statement.executeQuery(SHOW_FULL_PROCESSLIST).use { resultSet ->
            var found = false
            val queryIds = mutableListOf<String>()
            while (resultSet.next()) {
                val info = resultSet.getString("Info")
                val command = resultSet.getString("Command")
                if (!isRunningCancelCandidate(command, info, marker)) {
                    continue
                }
                // A visible row may not have an assigned QueryId yet. Remember it so that
                // the retry loop cannot mistake an unkillable-yet statement for release.
                found = true
                val queryId = resultSet.getString("QueryId")
                if (queryId != null && isValidQueryId(queryId)) {
                    queryIds.add(queryId)
                }
            }
            RunningQueries(found, queryIds)
        }

    private fun killQueryId(statement: Statement, queryId: String, marker: String) {
        try {
            statement.execute("""KILL QUERY "$queryId"""")
            stats.killsIssued.incrementAndGet()
            log.debug("cluster-scoped cancel: killed %s for %s", queryId, marker)
        } catch (e: SQLException) {
            if (isUnknownQueryId(e)) {
                log.debug("cluster-scoped cancel: %s already gone (%s)", queryId, marker)
            } else {
                log.debug(e, "cluster-scoped cancel: KILL QUERY %s failed for %s", queryId, marker)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Do not terminate the watchdog on best-effort socket cleanup failure.
    private fun abortHelper(helper: Connection, marker: String) {
        stats.helperTimeouts.incrementAndGet()
        try {
            helper.abort(directExecutor())
        } catch (e: Exception) {
            log.debug(e, "cluster-scoped cancel: helper abort failed for %s", marker)
        }
    }

    companion object {
        private val log = Logger.get(DorisClusterScopedCancel::class.java)

        private const val SHOW_FULL_PROCESSLIST = "SHOW FULL PROCESSLIST"

        /** Dispatch-to-expiry window shared by every probe of a request. */
        private const val KILL_WINDOW_MILLIS = 15_000L
        private const val KILL_RECHECK_MILLIS = 500L
        // Three empty probes span two retry intervals, allowing delayed FE visibility without a full-window search.
        private const val FINISHED_EMPTY_PROBE_LIMIT = 3
        private const val WORKER_COUNT = 8
        private const val QUEUE_CAPACITY = 64

        /** Shared per plugin classloader, not multiplied by the number of catalogs. */
        private val SHARED_EXECUTOR = newExecutor()
        private val RETRY_SCHEDULER = newRetryScheduler()
        private val SHARED_REQUEST_SLOTS = Semaphore(WORKER_COUNT + QUEUE_CAPACITY)

        internal fun newRetryScheduler(): ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "doris-cancel-retry").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(30, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }

        internal fun newExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
            WORKER_COUNT, WORKER_COUNT, 30, TimeUnit.SECONDS, ArrayBlockingQueue(QUEUE_CAPACITY),
            { runnable -> Thread(runnable, "doris-cluster-cancel").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }

        // Only executing helpers schedule tasks, so at most eight watchdogs are pending.
        private val WATCHDOG = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "doris-cancel-watchdog").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(30, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }

        /** Checked before the server-supplied QueryId (e.g. `ce96d055444242d1-a5a580d0c15a4c40`) is embedded in KILL. */
        internal fun isValidQueryId(value: String?): Boolean =
            value != null && QUERY_ID.matches(value)

        private val QUERY_ID = Regex("[0-9a-f]{1,16}-[0-9a-f]{1,16}")

        /**
         * The marker must start the statement: the same text can appear later inside a string
         * literal, identifier or comment, for example in a query searching the audit log. Idle
         * `Sleep` rows and the helper's own SHOW/KILL are skipped.
         */
        internal fun isRunningCancelCandidate(command: String?, info: String?, marker: String): Boolean {
            if (command == null || command.trim().equals("Sleep", ignoreCase = true) || !STATEMENT_MARKER.matches(marker)) {
                return false
            }
            val sql = info?.trimStart() ?: return false
            val prefix = "/*$marker*/ "
            if (!sql.startsWith(prefix)) {
                return false
            }
            val statement = sql.removePrefix(prefix).trimStart()
            return statement.isNotEmpty() &&
                !statement.startsWith(SHOW_FULL_PROCESSLIST, ignoreCase = true) &&
                !statement.startsWith("KILL QUERY", ignoreCase = true)
        }

        private val STATEMENT_MARKER = Regex("trino_statement_id=[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** Doris reports "Unknown query id" when the query has already finished. */
        internal fun isUnknownQueryId(throwable: Throwable?): Boolean {
            var current: Throwable? = throwable
            var hops = 0
            while (current != null && hops < 10) {
                if (current.message?.contains("Unknown query id", ignoreCase = true) == true) {
                    return true
                }
                current = current.cause?.takeIf { it !== current }
                hops++
            }
            return false
        }
    }
}
