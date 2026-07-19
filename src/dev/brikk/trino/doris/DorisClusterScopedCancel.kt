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

import io.airlift.log.Logger
import io.trino.plugin.jdbc.ConnectionFactory
import io.trino.spi.connector.ConnectorSession
import java.sql.Connection
import java.sql.SQLException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
 *  1. Every scan statement already carries the `/*trino_query_id=<id>*/` comment
 *     ([DorisRemoteQueryModifier]) — the probe proved `SHOW FULL PROCESSLIST` `Info` is
 *     UNTRUNCATED (120KB statement, trailing marker visible) and CLUSTER-WIDE on 4.1.3,
 *     so the existing marker suffices; no `session_context` trace-id bind is needed
 *     (and unlike the reference's per-CONNECTION guid, the marker is per-QUERY —
 *     exactly the cancel scope we want).
 *  2. At cancel: a short-lived HELPER connection (same URL — ANY FE is fine), widen with
 *     `SET fetch_all_fe_for_system_table = true` (defensive; 4.1.3 defaults cluster-wide),
 *     `SHOW FULL PROCESSLIST`, match the marker in `Info` (skipping idle rows and the
 *     helper's own statements), take the row's REAL `QueryId`, `KILL QUERY "<QueryId>"` —
 *     Doris forwards the kill FE-to-FE (probed: query on fe2, helper on fe3, released in
 *     22ms). NOT kill-by-trace-id: a documented silent no-op on 4.1.2 (the reference's
 *     production scar) — it happens to work on 4.1.3, but the QueryId path works on BOTH.
 *  3. The kill runs ASYNCHRONOUSLY: the busy streaming connection is NEVER touched at
 *     cancel time (even `getClientInfo` can stall ~8s mid-query — reference finding);
 *     marker bookkeeping lives in connector memory, keyed weakly by the scan connection.
 *     The blocked ResultSet read unblocks when the server errors the statement
 *     ("cancel query by user") — that is the completion signal.
 *
 * Every failure here degrades silently to the stock belt ([DorisClient.abortReadConnection]
 * keeps `connection.abort()`): a cancellation must never hang or fail because the helper
 * could not do its work. "Unknown query id" from the kill means the query already finished
 * — success, DEBUG.
 */
internal class DorisClusterScopedCancel(
    private val connectionFactory: ConnectionFactory,
    private val enabled: Boolean,
) {
    private class Registration(val marker: String, val session: ConnectorSession)

    /** Scan-connection -> (marker, session); entries die with the connection. */
    private val registrations: MutableMap<Connection, Registration> =
        Collections.synchronizedMap(WeakHashMap())

    /** Markers with a kill currently in flight (dedupe for multi-scan queries). */
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Called from the scan path at statement build time; overwrites any prior registration. */
    fun register(connection: Connection, session: ConnectorSession) {
        if (enabled) {
            registrations[connection] = Registration(DorisRemoteQueryModifier.marker(session.queryId), session)
        }
    }

    /**
     * Called from [DorisClient.abortReadConnection] BEFORE the socket abort: dispatches the
     * async cluster-scoped kill for the aborting scan's query. Never blocks, never throws.
     */
    @Suppress("TooGenericExceptionCaught") // best-effort BY CONTRACT: cancel must never fail because of the helper
    fun onAbort(connection: Connection) {
        if (!enabled) {
            return
        }
        val registration = registrations.remove(connection) ?: return
        if (!inFlight.add(registration.marker)) {
            return // another scan of the same query already dispatched the kill
        }
        killsDispatched.incrementAndGet()
        try {
            executor.execute { killQuietly(registration) }
        } catch (e: RuntimeException) {
            inFlight.remove(registration.marker)
            log.debug(e, "cluster-scoped cancel dispatch failed for %s", registration.marker)
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
    private fun killQuietly(registration: Registration) {
        try {
            connectionFactory.openConnection(registration.session).use { helper ->
                helper.createStatement().use { statement ->
                    // widen to all FEs — 4.1.3 already defaults cluster-wide, but the explicit
                    // SET is cheap insurance for configurations that flipped it back
                    runCatching { statement.execute("SET fetch_all_fe_for_system_table = true") }
                    killUntilReleased(statement, registration.marker)
                }
            }
        } catch (e: Exception) {
            // the stock socket-abort belt already ran; the helper is best-effort by contract
            log.debug(e, "cluster-scoped cancel helper failed for %s", registration.marker)
        } finally {
            inFlight.remove(registration.marker)
        }
    }

    private fun killUntilReleased(statement: java.sql.Statement, marker: String) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(KILL_WINDOW_MILLIS)
        while (true) {
            val queryIds = scanRunningQueryIds(statement, marker)
            if (queryIds.isEmpty()) {
                // finished or killed — that IS success for a cancel
                log.debug("cluster-scoped cancel: no running statement for %s", marker)
                return
            }
            for (queryId in queryIds) {
                killQueryId(statement, queryId, marker)
            }
            if (System.nanoTime() >= deadline) {
                log.debug(
                    "cluster-scoped cancel: %s still lists running rows after %sms — leaving to the socket belt",
                    marker,
                    KILL_WINDOW_MILLIS,
                )
                return
            }
            Thread.sleep(KILL_RECHECK_MILLIS)
        }
    }

    private fun scanRunningQueryIds(statement: java.sql.Statement, marker: String): List<String> =
        statement.executeQuery(SHOW_FULL_PROCESSLIST).use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    val info = resultSet.getString("Info")
                    val command = resultSet.getString("Command")
                    if (!isRunningCancelCandidate(command, info, marker)) {
                        continue
                    }
                    val queryId = resultSet.getString("QueryId")
                    if (isValidQueryId(queryId)) {
                        add(queryId)
                    }
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

        /**
         * Kill task executor: daemon threads, unbounded-but-rare (cancels), idle threads die.
         * Shared across catalogs — the tasks carry everything they need.
         */
        private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "doris-cluster-cancel").apply { isDaemon = true }
        }

        /** Test/diagnostic counters: dispatches are deterministic; issued kills can lose the race to the socket belt. */
        internal val killsDispatched = AtomicInteger()
        internal val killsIssued = AtomicInteger()

        /**
         * `QueryId` shape from SHOW FULL PROCESSLIST (e.g. `ce96d055444242d1-a5a580d0c15a4c40`),
         * validated before embedding in kill SQL (the value comes from a server result set).
         */
        internal fun isValidQueryId(value: String?): Boolean =
            value != null && value.matches(Regex("[0-9a-f]{1,16}-[0-9a-f]{1,16}"))

        /**
         * A row we may kill: RUNNING (not an idle `Sleep` connection), carrying OUR marker,
         * and not the helper's own SHOW/KILL statement. Unlike the reference (per-connection
         * guids, wrong-kill risk on ambiguity), the marker is per-QUERY here, so EVERY match
         * belongs to the query being cancelled — multi-scan queries kill all their scans.
         */
        internal fun isRunningCancelCandidate(command: String?, info: String?, marker: String): Boolean {
            if (command?.trim().equals("Sleep", ignoreCase = true)) {
                return false
            }
            val sql = info ?: return false
            if (sql.contains(SHOW_FULL_PROCESSLIST, ignoreCase = true) || sql.contains("KILL QUERY", ignoreCase = true)) {
                return false
            }
            return sql.contains(marker)
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
