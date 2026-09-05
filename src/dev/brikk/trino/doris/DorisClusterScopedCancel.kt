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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
 * live on Doris 4.1.3 single-FE and the 3-FE overlay, `NOTES-cancel-cluster-scoped.md`):
 *
 *  1. Each built scan receives a fresh `/*trino_statement_id=<uuid>*/` comment. The
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
 *  3. The kill runs ASYNCHRONOUSLY: the busy streaming connection is NEVER touched at
 *     cancel time (even `getClientInfo` can stall ~8s mid-query — reference finding);
 *     marker and execution state are captured by the scan's [DorisPreparedStatement].
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
) {
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
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            executor.execute {
                if (System.nanoTime() >= deadline) {
                    killsExpired.incrementAndGet()
                    log.debug("cluster-scoped cancel expired in queue for %s", marker)
                } else {
                    killQuietly(session, marker, executionFinished, deadline)
                }
            }
            killsDispatched.incrementAndGet()
        } catch (e: RejectedExecutionException) {
            killsRejected.incrementAndGet()
            log.debug(e, "cluster-scoped cancel dispatch failed for %s", marker)
        }
    }

    /**
     * The kill loop: scan -> kill each match -> verify -> retry, within [KILL_WINDOW_MILLIS].
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
    private fun killQuietly(session: ConnectorSession, marker: String, executionFinished: () -> Boolean, deadline: Long) {
        var watchdog: ScheduledFuture<*>? = null
        try {
            connectionFactory.openConnection(session, deadline).use { helper ->
                // Keep the watchdog armed through resource close too, which can perform network I/O.
                watchdog = WATCHDOG.schedule(
                    { abortHelper(helper, marker) },
                    maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS,
                )
                if (System.nanoTime() >= deadline) {
                    return
                }
                // Connector/J implements this as SO_TIMEOUT, not a numeric-ID KILL timer.
                helper.setNetworkTimeout(directExecutor(), DorisCancelDriver.NETWORK_TIMEOUT_MILLIS)
                helper.createStatement().use { statement ->
                    // widen to all FEs — 4.1.3 already defaults cluster-wide, but the explicit
                    // SET is cheap insurance for configurations that flipped it back
                    runCatching { statement.execute("SET fetch_all_fe_for_system_table = true") }
                    killUntilReleased(statement, marker, executionFinished, deadline)
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
    }

    private fun killUntilReleased(statement: java.sql.Statement, marker: String, executionFinished: () -> Boolean, deadline: Long) {
        var observed = false
        while (System.nanoTime() < deadline) {
            // Read BEFORE scanning: execution could start/finish while SHOW is in flight.
            val finishedBeforeScan = executionFinished()
            val queryIds = scanRunningQueryIds(statement, marker)
            if (queryIds.isEmpty() && finishedBeforeScan && observed) {
                // finished or killed — that IS success for a cancel
                log.debug("cluster-scoped cancel: no running statement for %s", marker)
                return
            }
            // Socket abort can finish client execution before the FE exposes a buffered
            // request. Until we observe this token, an empty scan is not proof of release.
            observed = observed || queryIds.isNotEmpty()
            for (queryId in queryIds) {
                if (System.nanoTime() >= deadline) {
                    return
                }
                if (isValidQueryId(queryId)) {
                    killQueryId(statement, requireNotNull(queryId), marker)
                }
            }
            val remaining = TimeUnit.NANOSECONDS.toMillis(maxOf(0, deadline - System.nanoTime()))
            Thread.sleep(minOf(KILL_RECHECK_MILLIS, remaining))
        }
        log.debug("cluster-scoped cancel: release of %s not confirmed before deadline — leaving to the socket belt", marker)
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
            killsIssued.incrementAndGet()
            log.debug("cluster-scoped cancel: killed %s for %s", queryId, marker)
        } catch (e: SQLException) {
            if (isUnknownQueryId(e)) {
                log.debug("cluster-scoped cancel: %s already gone (%s)", queryId, marker)
            } else {
                log.debug(e, "cluster-scoped cancel: KILL QUERY %s failed for %s", queryId, marker)
            }
        }
    }

    companion object {
        private val log = Logger.get(DorisClusterScopedCancel::class.java)

        private const val SHOW_FULL_PROCESSLIST = "SHOW FULL PROCESSLIST"

        /** Verify-and-retry window for the early-kill no-op race (see [killQuietly]). */
        private const val KILL_WINDOW_MILLIS = 15_000L
        private const val KILL_RECHECK_MILLIS = 500L

        /** Shared per plugin classloader, not multiplied by the number of catalogs. */
        private val SHARED_EXECUTOR = newExecutor()

        internal fun newExecutor(): ThreadPoolExecutor = ThreadPoolExecutor(
            8, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64),
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

        @Suppress("TooGenericExceptionCaught") // Do not terminate the watchdog on best-effort socket cleanup failure.
        private fun abortHelper(helper: Connection, marker: String) {
            helperTimeouts.incrementAndGet()
            try {
                helper.abort(directExecutor())
            } catch (e: Exception) {
                log.debug(e, "cluster-scoped cancel: helper abort failed for %s", marker)
            }
        }

        /** Test/diagnostic counters: dispatches are deterministic; issued kills can lose the race to the socket belt. */
        internal val killsDispatched = AtomicInteger()
        internal val killsIssued = AtomicInteger()
        internal val killsRejected = AtomicInteger()
        internal val killsExpired = AtomicInteger()
        internal val helperTimeouts = AtomicInteger()

        /**
         * `QueryId` shape from SHOW FULL PROCESSLIST (e.g. `ce96d055444242d1-a5a580d0c15a4c40`),
         * validated before embedding in kill SQL (the value comes from a server result set).
         */
        internal fun isValidQueryId(value: String?): Boolean =
            value != null && value.matches(Regex("[0-9a-f]{1,16}-[0-9a-f]{1,16}"))

        /**
         * A row we may kill: RUNNING (not an idle `Sleep` connection), carrying OUR marker,
         * and not the helper's own SHOW/KILL statement. Match the complete marker comment,
         * not a query-id prefix; production callers use a unique per-statement token.
         */
        internal fun isRunningCancelCandidate(command: String?, info: String?, marker: String): Boolean {
            if (command?.trim().equals("Sleep", ignoreCase = true)) {
                return false
            }
            val sql = info ?: return false
            if (sql.trimStart().startsWith(SHOW_FULL_PROCESSLIST, ignoreCase = true) ||
                sql.trimStart().startsWith("KILL QUERY", ignoreCase = true)
            ) {
                return false
            }
            return sql.contains("/*$marker*/")
        }

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
