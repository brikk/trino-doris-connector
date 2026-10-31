package dev.brikk.trino.doris

import com.mysql.cj.jdbc.JdbcStatement
import io.trino.testing.TestingConnectorSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Same-FE duplicate hosts isolate HA proxy locking; this is not cross-FE forwarding proof. */
class TestDorisR05Review {
    private val session = TestingConnectorSession.builder().build()
    private val urls = linkedMapOf(
        "single" to "jdbc:mysql://127.0.0.1:9130/",
        "comma" to "jdbc:mysql://127.0.0.1:9130,127.0.0.1:9130/",
        "loadbalance" to "jdbc:mysql:loadbalance://127.0.0.1:9130,127.0.0.1:9130/",
        "replication" to "jdbc:mysql:replication://127.0.0.1:9130,127.0.0.1:9130/",
    )

    @Test
    fun executePhaseFallbackMustNotWaitForTheBusyHaProxy() {
        DorisTestCluster.requireGoldenVersion()
        val slow = mutableListOf<String>()
        for ((topology, url) in urls) {
            for (helperFails in listOf(false, true)) {
                val millis = executePhase(topology, url, helperFails, concurrentClose = false)
                if (millis >= 1000) slow.add("$topology helperFails=$helperFails ${millis}ms")
            }
        }
        assertThat(slow).describedAs("R-05 synchronous abort waited for execution").isEmpty()
    }

    @Test
    fun concurrentCloseMustNotRemainBehindBusyHaCancellation() {
        val millis = executePhase("comma", urls.getValue("comma"), helperFails = false, concurrentClose = true)
        assertThat(millis).describedAs("R-05 concurrent close/abort wait in ms").isLessThan(1000)
    }

    @Test
    fun adoptedStreamingResultsHaveNoExecutePhaseProxyLock() {
        for ((topology, url) in urls) {
            DriverManager.getConnection(url, properties()).use { connection ->
                val raw = connection.prepareStatement("SELECT number FROM numbers('number'='100000')")
                raw.unwrap(JdbcStatement::class.java).enableStreamingResults()
                DorisPreparedStatement(raw, connection) {}.use { statement ->
                    val result = statement.executeQuery()
                    assertThat(result.next()).isTrue()
                    val started = System.nanoTime()
                    statement.cancel()
                    val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    // A post-abort ResultSet.close may try to drain a closed socket.
                    // Record that SQLException separately from the cancellation latency.
                    val cleanupError = runCatching { result.close() }.exceptionOrNull()
                    println("R05|topology=$topology|phase=stream|cancel_ms=$millis|cleanup_error=$cleanupError")
                    if (cleanupError != null) assertThat(cleanupError).isInstanceOf(SQLException::class.java)
                    assertThat(millis).isLessThan(1000)
                }
            }
        }
    }

    private fun executePhase(topology: String, url: String, helperFails: Boolean, concurrentClose: Boolean): Long {
        val marker = "trino_statement_id=${UUID.randomUUID()}"
        val attempted = AtomicInteger()
        val helper = DorisClusterScopedCancel(
            DorisCancelConnectionFactory { _, _ ->
                attempted.incrementAndGet()
                throw SQLException("R05 deliberately unavailable helper")
            }, enabled = helperFails,
        )
        val cancelThread = AtomicReference<Thread>()
        val executor = Executors.newFixedThreadPool(3) { task ->
            Thread(task, "r05-review-worker").apply { isDaemon = true }
        }
        try {
            DriverManager.getConnection(url, properties()).use { connection ->
                val raw = connection.prepareStatement("SELECT sleep(4) /*$marker*/")
                raw.unwrap(JdbcStatement::class.java).enableStreamingResults()
                val proxy = connection.javaClass.name
                val driver = connection.metaData.driverVersion
                val statement = DorisPreparedStatement(raw, connection) { finished -> helper.cancel(session, marker, finished) }
                val execution = executor.submit<String> {
                    runCatching { statement.executeQuery().use { result -> while (result.next()) { result.getObject(1) } } }
                        .exceptionOrNull()?.toString() ?: "completed"
                }
                awaitVisible(marker)
                val started = System.nanoTime()
                val cancellation = executor.submit {
                    cancelThread.set(Thread.currentThread())
                    statement.cancel()
                }
                var stack = "completed within 250ms"
                try {
                    cancellation.get(250, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    val thread = requireNotNull(cancelThread.get())
                    stack = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId(), 30).toString()
                }
                val close = if (concurrentClose) executor.submit<Long> {
                    val closeStarted = System.nanoTime()
                    statement.close()
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closeStarted)
                } else null
                cancellation.get(12, TimeUnit.SECONDS)
                val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                val closeMillis = close?.get(12, TimeUnit.SECONDS)
                val outcome = execution.get(12, TimeUnit.SECONDS)
                println("R05|topology=$topology|helperFails=$helperFails|concurrentClose=$concurrentClose|" +
                    "cancel_ms=$millis|close_ms=$closeMillis|attempts=${attempted.get()}|proxy=$proxy|driver=$driver|outcome=$outcome\n$stack")
                statement.close()
                if (helperFails) assertThat(attempted.get()).isPositive()
                return millis
            }
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun properties() = DorisClientModule.connectionProperties(DorisConfig()).apply {
        setProperty("user", "root")
        setProperty("socketTimeout", "12000")
    }

    private fun awaitVisible(marker: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (DorisTestCluster.runningDorisQueryId(marker) == null) {
            check(System.nanoTime() < deadline) { "R05 execution never became visible: $marker" }
            Thread.sleep(10)
        }
    }
}
