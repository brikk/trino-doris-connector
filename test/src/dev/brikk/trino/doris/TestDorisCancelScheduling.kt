package dev.brikk.trino.doris

import com.google.common.util.concurrent.MoreExecutors.directExecutor
import io.trino.testing.TestingConnectorSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Strict, deterministic JDBC models exercise the production dispatcher, not a copied retry loop. */
class TestDorisCancelScheduling {
    private val session = TestingConnectorSession.builder().build()
    private val resources = DorisCancelTestResources()

    @AfterEach
    fun closeResources() = resources.close()

    @Test
    fun testEightAbsentFinishedScansDoNotStarveALiveNinthRequest() {
        repeat(3) {
            val executor = DorisClusterScopedCancel.newExecutor()
            val scheduler = DorisClusterScopedCancel.newRetryScheduler()
            val slots = Semaphore(72)
            val model = Model(liveMarker = marker(), firstCloses = 8)
            val cancel = resources.newCancel(model.factory, true, executor, 5000, scheduler, slots)
            try {
                repeat(8) { cancel.cancel(session, marker()) { true } }
                assertThat(model.closed.await(2, SECONDS)).describedAs("absent probes closed before retry window ends").isTrue()
                cancel.cancel(session, requireNotNull(model.liveMarker)) { true }
                assertThat(model.killed.await(2, SECONDS)).describedAs("live ninth request gets a worker before absent requests expire").isTrue()
                assertThat(slots.tryAcquire(72, 8, SECONDS)).describedAs("all logical requests release admission").isTrue()
                assertThat(model.active.get()).isZero()
                assertThat(model.maximumActive.get()).isLessThanOrEqualTo(8)
                assertThat(model.failures).isEmpty()
                assertThat(scheduler.queue).isEmpty()
            } finally {
                scheduler.shutdownNow()
                executor.shutdownNow()
                assertThat(scheduler.awaitTermination(5, SECONDS)).isTrue()
                assertThat(executor.awaitTermination(5, SECONDS)).isTrue()
            }
        }
    }

    @Test
    fun testDelayedRequestsRemainBoundedEvenWhenWorkersAndConnectionsAreFree() {
        val executor = DorisClusterScopedCancel.newExecutor()
        val scheduler = DorisClusterScopedCancel.newRetryScheduler()
        val slots = Semaphore(72)
        val model = Model(firstCloses = 72)
        val cancel = resources.newCancel(model.factory, true, executor, 5000, scheduler, slots)
        try {
            repeat(72) { cancel.cancel(session, marker()) { true } }
            assertThat(model.closed.await(2, SECONDS)).isTrue()
            assertThat(slots.availablePermits()).isZero()
            assertThat(scheduler.queue.size).isLessThanOrEqualTo(72)
            val rejected = cancel.stats.killsRejected.get()
            cancel.cancel(session, marker()) { true }
            assertThat(cancel.stats.killsRejected.get()).isEqualTo(rejected + 1)
            assertThat(model.maximumActive.get()).isLessThanOrEqualTo(8)
            assertThat(slots.tryAcquire(72, 8, SECONDS)).isTrue()
            assertThat(model.active.get()).isZero()
            assertThat(model.failures).isEmpty()
        } finally {
            scheduler.shutdownNow()
            executor.shutdownNow()
            assertThat(scheduler.awaitTermination(5, SECONDS)).isTrue()
            assertThat(executor.awaitTermination(5, SECONDS)).isTrue()
        }
    }

    @Test
    fun testReconnectedProbesKeepTheOriginalDeadlineAndDoNotRenewTheWindow() {
        val executor = DorisClusterScopedCancel.newExecutor()
        val scheduler = DorisClusterScopedCancel.newRetryScheduler()
        val slots = Semaphore(1)
        val model = Model()
        try {
            val started = System.nanoTime()
            resources.newCancel(model.factory, true, executor, 1600, scheduler, slots).cancel(session, marker()) { false }
            assertThat(slots.tryAcquire(1, 4, SECONDS)).isTrue()
            assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1600))
            assertThat(model.deadlines.size).isGreaterThanOrEqualTo(3)
            assertThat(model.deadlines.distinct()).hasSize(1)
            assertThat(model.active.get()).isZero()
            assertThat(model.failures).isEmpty()
        } finally {
            scheduler.shutdownNow()
            executor.shutdownNow()
            assertThat(scheduler.awaitTermination(5, SECONDS)).isTrue()
            assertThat(executor.awaitTermination(5, SECONDS)).isTrue()
        }
    }

    @Test
    fun testFinishedNeverObservedStatementStopsAfterThreeEmptyProbes() {
        val executor = DorisClusterScopedCancel.newExecutor()
        val scheduler = DorisClusterScopedCancel.newRetryScheduler()
        val slots = Semaphore(1)
        val model = Model()
        try {
            resources.newCancel(model.factory, true, executor, 5000, scheduler, slots).cancel(session, marker()) { true }
            assertThat(slots.tryAcquire(1, 3, SECONDS)).isTrue()
            assertThat(model.deadlines).hasSize(3)
            assertThat(model.active.get()).isZero()
            assertThat(model.failures).isEmpty()
            assertThat(scheduler.queue).isEmpty()
        } finally {
            scheduler.shutdownNow()
            executor.shutdownNow()
            assertThat(scheduler.awaitTermination(5, SECONDS)).isTrue()
            assertThat(executor.awaitTermination(5, SECONDS)).isTrue()
        }
    }

    @Test
    fun testObservedStatementStopsOnItsFirstEmptyProbe() {
        val executor = DorisClusterScopedCancel.newExecutor()
        val scheduler = DorisClusterScopedCancel.newRetryScheduler()
        val slots = Semaphore(1)
        val model = Model(liveMarker = marker())
        try {
            resources.newCancel(model.factory, true, executor, 5000, scheduler, slots)
                .cancel(session, requireNotNull(model.liveMarker)) { true }
            assertThat(slots.tryAcquire(1, 3, SECONDS)).isTrue()
            assertThat(model.deadlines).hasSize(2)
            assertThat(model.killed.count).isZero()
            assertThat(model.failures).isEmpty()
            assertThat(scheduler.queue).isEmpty()
        } finally {
            scheduler.shutdownNow()
            executor.shutdownNow()
            assertThat(scheduler.awaitTermination(5, SECONDS)).isTrue()
            assertThat(executor.awaitTermination(5, SECONDS)).isTrue()
        }
    }

    @Test
    fun testWorkerOrSchedulerRejectionReleasesAdmissionAndNeverEscapes() {
        for (rejectWorker in listOf(false, true)) {
            val scheduler = DorisClusterScopedCancel.newRetryScheduler().also { it.shutdown() }
            val slots = Semaphore(1)
            val model = Model()
            val executor = if (rejectWorker) Executor { throw RejectedExecutionException("scripted worker rejection") } else directExecutor()
            val cancel = resources.newCancel(model.factory, true, executor, 5000, scheduler, slots)
            cancel.cancel(session, marker()) { true }
            assertThat(slots.availablePermits()).isEqualTo(1)
            assertThat(model.active.get()).isZero()
            assertThat(model.deadlines).hasSize(if (rejectWorker) 0 else 1)
            assertThat(cancel.stats.killsRejected.get()).isEqualTo(1)
            assertThat(model.failures).isEmpty()
        }
    }

    private class Model(val liveMarker: String? = null, firstCloses: Int = 1) {
        val closed = CountDownLatch(firstCloses)
        val killed = CountDownLatch(1)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val failures = CopyOnWriteArrayList<Throwable>()
        val deadlines = CopyOnWriteArrayList<Long>()
        private val running = AtomicBoolean(liveMarker != null)
        val factory = DorisCancelConnectionFactory { _, deadline ->
            deadlines.add(deadline)
            val count = active.incrementAndGet()
            maximumActive.updateAndGet { maxOf(it, count) }
            connection()
        }

        private fun connection(): Connection {
            val didClose = AtomicBoolean()
            return proxy(Connection::class.java) { method, _ ->
                when (method) {
                    "createStatement" -> statement()
                    "setNetworkTimeout", "abort" -> null
                    "close" -> {
                        check(didClose.compareAndSet(false, true)) { "Connection closed twice" }
                        active.decrementAndGet()
                        closed.countDown()
                        null
                    }
                    else -> error("Unexpected helper connection call: $method")
                }
            }
        }

        private fun statement(): Statement = proxy(Statement::class.java) { method, arguments ->
            when (method) {
                "execute" -> {
                    val sql = arguments[0] as String
                    if (sql != "SET fetch_all_fe_for_system_table = true") {
                        check(sql == "KILL QUERY \"a-b\"") { "Wrong/unvalidated kill: $sql" }
                        running.set(false)
                        killed.countDown()
                    }
                    false
                }
                "executeQuery" -> {
                    check(arguments[0] == "SHOW FULL PROCESSLIST")
                    processlist()
                }
                "close" -> null
                else -> error("Unexpected helper statement call $method")
            }
        }

        private fun processlist(): ResultSet {
            var row = running.get()
            return proxy(ResultSet::class.java) { operation, columns ->
                when (operation) {
                    "next" -> row.also { row = false }
                    "getString" -> when (columns[0]) {
                        "Info" -> "/*$liveMarker*/ SELECT 1"
                        "Command" -> "Query"
                        "QueryId" -> "a-b"
                        else -> error("Unexpected processlist column")
                    }
                    "close" -> null
                    else -> error("Unexpected processlist call $operation")
                }
            }
        }

        private fun <T> proxy(type: Class<T>, handler: (String, Array<out Any?>) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                try {
                    handler(method.name, args ?: emptyArray())
                } catch (failure: Throwable) {
                    if (failure !is SQLException) failures.add(failure)
                    throw failure
                }
            })
    }

    private fun marker(): String = "trino_statement_id=${UUID.randomUUID()}"
}

internal class DorisCancelTestResources : AutoCloseable {
    private val executor = DorisClusterScopedCancel.newExecutor()
    private val scheduler = DorisClusterScopedCancel.newRetryScheduler()
    private val watchdog = DorisClusterScopedCancel.newRetryScheduler()

    fun newCancel(
        connectionFactory: DorisCancelConnectionFactory,
        enabled: Boolean = true,
        executor: Executor = this.executor,
        timeoutMillis: Long = 15_000,
        retryScheduler: ScheduledExecutorService = scheduler,
        requestSlots: Semaphore = Semaphore(1),
    ): DorisClusterScopedCancel = DorisClusterScopedCancel(
        connectionFactory, enabled, executor, timeoutMillis, retryScheduler, requestSlots, watchdog,
    )

    override fun close() {
        val services = listOf(executor, scheduler, watchdog)
        services.forEach { it.shutdownNow() }
        services.forEach { assertThat(it.awaitTermination(5, SECONDS)).isTrue() }
    }
}
