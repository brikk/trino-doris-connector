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
import io.trino.testing.TestingConnectorSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class TestDorisCancelLimits {
    private val session = TestingConnectorSession.builder().build()
    private val marker = "trino_statement_id=00000000-0000-0000-0000-000000000001"
    private val failures = CopyOnWriteArrayList<Throwable>()

    @Test
    @Suppress("LongMethod", "ThrowsCount") // Saturation and wrapper fallback share strict fakes and one held gate.
    fun testSaturationRejectsWithoutCallerWorkAndStillAbortsScan() {
        val executor = DorisClusterScopedCancel.newExecutor()
        val caller = newWorker("cancel-limit-caller")
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(8)
        val threads = CopyOnWriteArrayList<String>()
        val cancel = DorisClusterScopedCancel(
            DorisCancelConnectionFactory { _, _ ->
                threads.add(Thread.currentThread().name)
                entered.countDown()
                await(gate)
                throw SQLException("helper connection unavailable")
            },
            enabled = true,
            executor = executor,
        )
        val aborts = AtomicInteger()
        val rawCancels = AtomicInteger()
        val result = jdbcProxy(ResultSet::class.java) { method, _ ->
            throw AssertionError("unexpected result call: $method")
        }
        val delegate = jdbcProxy(PreparedStatement::class.java) { method, _ ->
            when (method) {
                "executeQuery" -> result
                "cancel" -> { rawCancels.incrementAndGet(); null }
                "close" -> null
                else -> throw AssertionError("unexpected scan call: $method")
            }
        }
        val connection = jdbcProxy(Connection::class.java) { method, args ->
            assertThat(method).isEqualTo("abort")
            assertThat(args[0]).isSameAs(directExecutor())
            aborts.incrementAndGet()
            null
        }
        val statement = DorisPreparedStatement(delegate, connection) { finished -> cancel.cancel(session, marker, finished) }
        val dispatched = DorisClusterScopedCancel.killsDispatched.get()
        val rejected = DorisClusterScopedCancel.killsRejected.get()
        try {
            assertThat(executor.corePoolSize).isEqualTo(8)
            assertThat(executor.maximumPoolSize).isEqualTo(8)
            assertThat(executor.allowsCoreThreadTimeOut()).isTrue()
            assertThat(executor.rejectedExecutionHandler).isInstanceOf(ThreadPoolExecutor.AbortPolicy::class.java)
            caller.submit { repeat(72) { cancel.cancel(session, marker) { false } } }.get(1, SECONDS)
            await(entered)
            assertThat(executor.poolSize).isEqualTo(8)
            assertThat(executor.queue.size).isEqualTo(64)
            assertThat(executor.queue.remainingCapacity()).isZero()
            caller.submit {
                statement.executeQuery()
                statement.cancel()
                statement.close()
            }.get(1, SECONDS)
            assertThat(gate.count).isEqualTo(1L)
            assertThat(aborts.get()).isEqualTo(1)
            assertThat(rawCancels.get()).isZero()
            assertThat(threads).hasSize(8).containsOnly("doris-cluster-cancel")
            assertThat(DorisClusterScopedCancel.killsDispatched.get()).isEqualTo(dispatched + 72)
            assertThat(DorisClusterScopedCancel.killsRejected.get()).isEqualTo(rejected + 1)
            gate.countDown()
            executor.shutdown()
            assertThat(executor.awaitTermination(5, SECONDS)).isTrue()
            assertThat(threads).hasSize(72).containsOnly("doris-cluster-cancel")
            assertThat(failures).isEmpty()
        } finally {
            gate.countDown()
            shutdown(caller, executor)
        }
    }

    @Test
    fun testExpiredQueuedRequestNeverOpensConnection() {
        val executor = newWorker()
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val opens = AtomicInteger()
        val cancel = DorisClusterScopedCancel(
            DorisCancelConnectionFactory { _, _ -> opens.incrementAndGet(); throw SQLException("must not open") },
            enabled = true,
            executor = executor,
            timeoutMillis = 200,
        )
        val dispatched = DorisClusterScopedCancel.killsDispatched.get()
        val expired = DorisClusterScopedCancel.killsExpired.get()
        try {
            val blocker = executor.submit { entered.countDown(); await(gate) }
            await(entered)
            val started = System.nanoTime()
            cancel.cancel(session, marker) { false }
            assertThat(System.nanoTime() - started).isLessThan(SECONDS.toNanos(1))
            assertThat(DorisClusterScopedCancel.killsDispatched.get()).isEqualTo(dispatched + 1)
            // Start the timed hold AFTER dispatch, so the queued deadline has certainly elapsed.
            assertThat(gate.await(400, MILLISECONDS)).isFalse()
            gate.countDown()
            blocker.get(5, SECONDS)
            executor.submit {}.get(5, SECONDS)
            assertThat(DorisClusterScopedCancel.killsExpired.get()).isGreaterThanOrEqualTo(expired + 1)
            assertThat(opens.get()).isZero()
        } finally {
            gate.countDown()
            shutdown(executor)
        }
    }

    @Test
    fun testConnectionFactoryReceivesOriginalDispatchDeadline() {
        var task: Runnable? = null
        var passedDeadline = 0L
        val timeoutMillis = 5000L
        val cancel = DorisClusterScopedCancel(
            DorisCancelConnectionFactory { _, deadline ->
                passedDeadline = deadline
                throw SQLException("deadline recorded")
            },
            enabled = true,
            executor = Executor { task = it },
            timeoutMillis = timeoutMillis,
        )
        val started = System.nanoTime()
        cancel.cancel(session, marker) { false }
        val dispatched = System.nanoTime()
        requireNotNull(task).run()
        assertThat(passedDeadline).isBetween(started + MILLISECONDS.toNanos(timeoutMillis), dispatched + MILLISECONDS.toNanos(timeoutMillis))
    }

    @Test
    fun testWatchdogAbortsStalledShowAndReleasesWorker() = assertWatchdogReleasesHelper(stallClose = false)

    @Test
    fun testWatchdogRemainsArmedDuringConnectionClose() = assertWatchdogReleasesHelper(stallClose = true)

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ThrowsCount") // Shared strict JDBC script.
    private fun assertWatchdogReleasesHelper(stallClose: Boolean) {
        val executor = newWorker()
        val stalled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val networkTimeout = AtomicInteger()
        val statement = jdbcProxy(Statement::class.java) { method, args ->
            when (method) {
                "execute" -> {
                    assertThat(args[0]).isEqualTo("SET fetch_all_fe_for_system_table = true")
                    events.add("SET")
                    false
                }
                "executeQuery" -> {
                    assertThat(args[0]).isEqualTo("SHOW FULL PROCESSLIST")
                    events.add("SHOW")
                    if (!stallClose) {
                        stalled.countDown()
                        await(release)
                    }
                    throw SQLException("helper query failed")
                }
                "close" -> { events.add("statement.close"); null }
                else -> throw AssertionError("unsafe or unexpected helper statement call: $method")
            }
        }
        val connection = jdbcProxy(Connection::class.java) { method, args ->
            when (method) {
                "setNetworkTimeout" -> {
                    assertThat(args[0]).isSameAs(directExecutor())
                    networkTimeout.set(args[1] as Int)
                    events.add("networkTimeout")
                    null
                }
                "createStatement" -> statement
                "abort" -> {
                    assertThat(args[0]).isSameAs(directExecutor())
                    events.add("abort")
                    release.countDown()
                    null
                }
                "close" -> {
                    events.add("connection.close.enter")
                    if (stallClose) {
                        stalled.countDown()
                        await(release)
                    }
                    events.add("connection.close.exit")
                    closed.countDown()
                    null
                }
                else -> throw AssertionError("unexpected helper connection call: $method")
            }
        }
        val timeouts = DorisClusterScopedCancel.helperTimeouts.get()
        try {
            executor.submit {}.get(5, SECONDS)
            DorisClusterScopedCancel({ _, _ -> connection }, enabled = true, executor = executor, timeoutMillis = 250)
                .cancel(session, marker) { false }
            val nextWork = executor.submit { events.add("next work") }
            await(stalled)
            await(closed)
            nextWork.get(5, SECONDS)
            assertThat(networkTimeout.get()).isEqualTo(2000).isEqualTo(DorisCancelDriver.NETWORK_TIMEOUT_MILLIS)
            assertThat(DorisClusterScopedCancel.helperTimeouts.get()).isGreaterThanOrEqualTo(timeouts + 1)
            val cleanup = if (stallClose) {
                listOf("statement.close", "connection.close.enter", "abort", "connection.close.exit")
            } else {
                listOf("abort", "statement.close", "connection.close.enter", "connection.close.exit")
            }
            assertThat(events).containsExactlyElementsOf(listOf("networkTimeout", "SET", "SHOW") + cleanup + "next work")
            assertThat(failures).isEmpty()
        } finally {
            release.countDown()
            shutdown(executor)
        }
    }

    @Test
    fun testConnectionOpenedAfterDeadlineIsClosedWithoutSql() {
        val executor = newWorker()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val aborted = CountDownLatch(1)
        val closes = AtomicInteger()
        val connection = jdbcProxy(Connection::class.java) { method, args ->
            when (method) {
                // Drain the zero-delay watchdog before the next test snapshots global counters.
                "close" -> { await(aborted); closes.incrementAndGet(); null }
                "abort" -> { assertThat(args[0]).isSameAs(directExecutor()); aborted.countDown(); null }
                else -> throw AssertionError("expired helper must not configure or execute SQL: $method")
            }
        }
        val cancel = DorisClusterScopedCancel(
            DorisCancelConnectionFactory { _, _ -> entered.countDown(); await(gate); connection },
            enabled = true,
            executor = executor,
            timeoutMillis = 200,
        )
        try {
            cancel.cancel(session, marker) { throw AssertionError("expired helper must not scan") }
            await(entered)
            assertThat(gate.await(400, MILLISECONDS)).isFalse()
            gate.countDown()
            executor.submit {}.get(5, SECONDS)
            assertThat(closes.get()).isEqualTo(1)
            assertThat(failures).isEmpty()
        } finally {
            gate.countDown()
            aborted.countDown()
            shutdown(executor)
        }
    }

    private fun newWorker(name: String = "cancel-limit-helper"): ExecutorService =
        Executors.newSingleThreadExecutor { task -> Thread(task, name).apply { isDaemon = true } }

    private fun await(latch: CountDownLatch) = assertThat(latch.await(5, SECONDS)).describedAs("gate released within 5s").isTrue()

    private fun shutdown(vararg executors: ExecutorService) {
        executors.forEach { it.shutdownNow() }
        executors.forEach { assertThat(it.awaitTermination(5, SECONDS)).describedAs("worker terminated").isTrue() }
    }

    private fun <T> jdbcProxy(type: Class<T>, handler: (String, Array<out Any?>) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            try {
                handler(method.name, args ?: emptyArray())
            } catch (failure: Throwable) {
                // Helper failures are swallowed in production; surface strict-fake violations on the test thread.
                if (failure !is SQLException) failures.add(failure)
                throw failure
            }
        })
}
