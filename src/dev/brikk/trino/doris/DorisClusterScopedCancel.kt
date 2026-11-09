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
 * CLUSTER-SCOPED query cancellation (multi-FE / load-balancer correct).
 *
 * The defect this fixes (user-reported from a REAL multi-FE deployment): Connector/J's
 * `Statement.cancel()` opens a SECOND connection to the same JDBC URL and issues
 * `KILL QUERY <connection-id>`. Behind a load balancer over multiple FEs that lands on an
 * ARBITRARY FE where connection ids are per-FE counters — a silent no-op, or a kill of an
 * unrelated same-user session. Likewise, a plain socket abort only releases the Doris work
 * when the OWNING FE observes the teardown promptly.
 *
 * The working sequence (adapted, with credit, from the Apache-2.0 reference implementation
 * in dev.sort.doris.cancel — production-hardened against a real LB'd cluster; re-proven
 * live on Doris 4.1.3 single-FE and the 3-FE overlay, `dev-docs/archive/NOTES-cancel-cluster-scoped.md`):
 *
 *  1. Each built scan starts with a fresh `/*trino_statement_id=<uuid>*/` comment. The
 *     Trino query-id comment remains for tracing, but is NOT a cancellation key: Trino
 *     closes scans early for LIMIT as well as on failure, without cancelling sibling scans.
 *     The probe proved SHOW FULL PROCESSLIST Info is untruncated and cluster-wide on 4.1.3.
 *  2. At cancel: a short-lived HELPER connection (same URL — ANY FE is fine), widen with
 *     `SET fetch_all_fe_for_system_table = true` (defensive; 4.1.3 defaults cluster-wide),
 *     `SHOW FULL PROCESSLIST`, match the marker in `Info` (skipping idle rows and the
 *     helper's own statements), take the row's REAL `QueryId`, `KILL QUERY "<QueryId>"` —
 *     Doris forwards the kill FE-to-FE (probed: query on fe2, helper on fe3, released in
 *     22ms). NOT kill-by-trace-id: a documented silent no-op on 4.1.2 (the reference's
 *     production scar) — it happens to work on 4.1.3, but the QueryId path works on BOTH.
 *  3. The helper runs ASYNCHRONOUSLY and never consults the busy scan connection
 *     (even `getClientInfo` can stall mid-query). Marker/state are captured by
 *     [DorisPreparedStatement], whose independent physical socket abort remains.
 *     Each probe closes its helper before scheduling a retry; no worker or idle
 *     connection is parked between probes. At most 72 requests are admitted per
 *     plugin classloader, including running, queued and delayed requests.
 *     The blocked ResultSet read unblocks when the server errors the statement
 *     ("cancel query by user") — that is the completion signal.
 *
 * Every failure here degrades to the socket belt ([DorisPreparedStatement] aborts the
 * captured connection): a cancellation must never hang or fail because the helper
 * could not do its work. "Unknown query id" from the kill means the query already finished
 * — success, DEBUG.
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
     * Called once by the scan wrapper before socket abort. No busy JDBC calls on this thread.
     * executionFinished must reflect delegate execution exit, not just cancellation/close.
     */
    fun cancel(session: ConnectorSession, marker: String, executionFinished: () -> Boolean) {
        if (!enabled) {
            return
        }
        // Includes running, queued AND delayed requests, across catalogs in this classloader.
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

    /** One logical request retains its observation state/deadline, never an idle JDBC connection. */
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
            if (completed.get()) return false
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
            if (completed.get()) return
            if (System.nanoTime() >= deadline) {
                finish(expired = true)
                return
            }
            var retry = false
            try {
                retry = probeQuietly(session, marker, deadline) { statement ->
                    // Read BEFORE scanning: completion during SHOW cannot prove absence.
                    val finishedBeforeScan = executionFinished()
                    val queryIds = scanRunningQueryIds(statement, marker)
                    if (isGone(queryIds.isEmpty(), finishedBeforeScan)) {
                        false
                    } else {
                        observed = observed || queryIds.isNotEmpty()
                        for (queryId in queryIds) {
                            if (System.nanoTime() >= deadline) break
                            if (isValidQueryId(queryId)) killQueryId(statement, requireNotNull(queryId), marker)
                        }
                        true
                    }
                }
            } finally {
                if (!retry) finish() // also release admission on unexpected unchecked failures
            }
            // probeQuietly has closed all JDBC resources (watchdog still armed through close).
            if (retry) scheduleRetry()
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
                retryScheduler.schedule({ dispatch() }, minOf(TimeUnit.MILLISECONDS.toNanos(KILL_RECHECK_MILLIS), remaining), TimeUnit.NANOSECONDS)
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
     * One short probe in the scan -> kill -> close -> scheduled retry chain.
     *
     * The retry is NOT paranoia — it is a live-reproduced Doris 4.1.3 race: a
     * `KILL QUERY "<QueryId>"` issued in the FIRST INSTANTS of a query's life (the
     * processlist row appears before the coordinator registers as cancellable) returns OK
     * but is a SILENT NO-OP and the query runs to completion (probe: 1/25 tight-loop rounds;
     * the victim COMPLETED normally despite the accepted kill). Re-scanning until the row is
     * gone closes the window. A row that outlives the whole window is logged at DEBUG and
     * left to the socket-abort belt (it can also be the killed-but-lingering send-blocked
     * row from the CI finding — repeat kills of those are silently accepted and harmless).
     */
    @Suppress("TooGenericExceptionCaught") // best-effort BY CONTRACT: the stock abort belt already ran
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
            // the stock socket-abort belt already ran; the helper is best-effort by contract
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

    private fun scanRunningQueryIds(statement: java.sql.Statement, marker: String): List<String?> =
        statement.executeQuery(SHOW_FULL_PROCESSLIST).use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    val info = resultSet.getString("Info")
                    val command = resultSet.getString("Command")
                    if (!isRunningCancelCandidate(command, info, marker)) {
                        continue
                    }
                    // A visible row may not have an assigned QueryId yet. Keep it so that
                    // the retry loop cannot mistake an unkillable-yet statement for release.
                    add(resultSet.getString("QueryId"))
                }
            }
        }

    private fun killQueryId(statement: java.sql.Statement, queryId: String, marker: String) {
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

        /** Original dispatch-to-expiry window, shared by every probe in a request. */
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

        /**
         * `QueryId` shape from SHOW FULL PROCESSLIST (e.g. `ce96d055444242d1-a5a580d0c15a4c40`),
         * validated before embedding in kill SQL (the value comes from a server result set).
         */
        internal fun isValidQueryId(value: String?): Boolean =
            value != null && value.matches(Regex("[0-9a-f]{1,16}-[0-9a-f]{1,16}"))

        /**
         * A row we may kill: RUNNING (not an idle `Sleep` connection), carrying OUR marker,
         * and not the helper's own SHOW/KILL statement. The connector places its unique
         * token at the SQL boundary, BEFORE all generated SQL and tracing comments.
         * Never search arbitrary text: even an exact comment can occur inside an audit
         * search's string literal, quoted identifier, line comment or outer block comment.
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

        /** "Unknown query id" (any hop of the cause chain) == the query already finished. */
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
