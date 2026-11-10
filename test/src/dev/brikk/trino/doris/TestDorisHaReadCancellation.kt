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

import com.mysql.cj.jdbc.JdbcConnection
import com.mysql.cj.jdbc.JdbcStatement
import io.trino.testing.TestingConnectorSession
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Connector/J HA connections; duplicate hosts isolate locking, not cross-FE routing. */
class TestDorisHaReadCancellation {
    private val session = TestingConnectorSession.builder().build()
    private val resources = DorisCancelTestResources()

    @AfterEach
    fun closeResources() = resources.close()

    private val urls = linkedMapOf(
        "single" to "jdbc:mysql://127.0.0.1:9130/",
        "comma" to "jdbc:mysql://127.0.0.1:9130,127.0.0.1:9130/",
        "loadbalance" to "jdbc:mysql:loadbalance://127.0.0.1:9130,127.0.0.1:9130/",
        "replication" to "jdbc:mysql:replication://127.0.0.1:9130,127.0.0.1:9130/",
    )

    @Test
    fun testBindingsAndPreparedModesSurvivePhysicalPinning() {
        for (url in urls.values) {
            for (serverPrepared in listOf(false, true)) {
                readConnection("$url?useServerPrepStmts=$serverPrepared").use { connection ->
                    assertBindings(connection)
                }
            }
        }
    }

    @Test
    fun testUnknownConnectionCannotFallBackToAnUnsafeProxy() {
        val closes = AtomicInteger()
        val owner = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "setReadOnly" -> null
                "unwrap" -> throw SQLException("unsupported physical identity")
                "close" -> { closes.incrementAndGet(); null }
                else -> error("Unexpected call ${method.name}")
            }
        } as Connection
        assertThatThrownBy { DorisReadDriver.pin(owner) }.isInstanceOf(SQLException::class.java)
        assertThat(closes.get()).isEqualTo(1)
    }

    @Test
    fun testPinFailurePreservesCloseFailure() {
        val pinFailure = SQLException("unsupported physical identity")
        val closeFailure = SQLException("owner close failed")
        val owner = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "setReadOnly" -> null
                "unwrap" -> throw pinFailure
                "close" -> throw closeFailure
                else -> error("Unexpected call ${method.name}")
            }
        } as Connection
        assertThatThrownBy { DorisReadDriver.pin(owner) }.isSameAs(pinFailure)
        assertThat(pinFailure.suppressed).containsExactly(closeFailure)
    }

    @Test
    fun testReadOnlySetupFailureClosesOwner() {
        val failure = SQLException("read-only setup failed")
        val closes = AtomicInteger()
        val owner = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "setReadOnly" -> throw failure
                "close" -> { closes.incrementAndGet(); null }
                else -> error("Unexpected call ${method.name}")
            }
        } as Connection
        assertThatThrownBy { DorisReadDriver.pin(owner) }.isSameAs(failure)
        assertThat(closes.get()).isEqualTo(1)
    }

    @Test
    fun testExecutePhaseFallbackDoesNotWaitForHaProxy() {
        DorisTestCluster.requireGoldenVersion()
        for ((topology, url) in urls) {
            for (helperFails in listOf(false, true)) {
                assertThat(executePhase(topology, url, helperFails, concurrentClose = false))
                    .describedAs("$topology helperFails=$helperFails ms").isLessThan(1000)
            }
        }
    }

    @Test
    fun testConcurrentCloseDoesNotRemainBehindHaCancellation() {
        assertThat(executePhase("comma", urls.getValue("comma"), helperFails = false, concurrentClose = true))
            .isLessThan(1000)
    }

    @Test
    fun testAdoptedStreamingResultsAbortPromptly() {
        for ((topology, url) in urls) {
            readConnection(url).use { connection ->
                val raw = connection.prepareStatement("SELECT number FROM numbers('number'='100000')")
                raw.unwrap(JdbcStatement::class.java).enableStreamingResults()
                DorisPreparedStatement(raw, connection) { _, _ -> }.use { statement ->
                    val result = statement.executeQuery()
                    assertThat(result.next()).isTrue()
                    val started = System.nanoTime()
                    statement.cancel()
                    val millis = elapsed(started)
                    val cleanupError = runCatching { result.close() }.exceptionOrNull()
                    println("R05|topology=$topology|phase=stream|cancel_ms=$millis|cleanup=${cleanupError?.javaClass?.simpleName}")
                    if (cleanupError != null) assertThat(cleanupError).isInstanceOf(SQLException::class.java)
                    assertThat(millis).isLessThan(1000)
                }
            }
        }
    }

    @Test
    fun testNewConnectionStillFailsOverPastUnavailableFirstHost() {
        readConnection("jdbc:mysql://127.0.0.1:1,127.0.0.1:9130/?connectTimeout=200").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1").use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getInt(1)).isEqualTo(1)
                }
            }
        }
    }

    @Test
    fun testReadOnlyConnectionsAllowScanSessionSetup() {
        for (url in urls.values) {
            readConnection(url).use { connection ->
                connection.isReadOnly = true
                connection.createStatement().use { statement ->
                    statement.execute("SET query_timeout = 30")
                    statement.executeQuery("SELECT @@query_timeout").use { result ->
                        assertThat(result.next()).isTrue()
                        assertThat(result.getInt(1)).isEqualTo(30)
                    }
                }
            }
        }
    }

    @Test
    fun testReplicationReadOnlyRoutingInProductionOrder() {
        val url = "jdbc:mysql:replication://127.0.0.1:9130,172.30.81.10:9030/"
        val pinnedHost = readConnection(url).use { connection ->
            connection.isReadOnly = true
            statementHost(connection)
        }
        val plainHost = DorisTestSafety.connectSingle(url, properties()).use { connection ->
            connection.isReadOnly = true
            statementHost(connection)
        }
        println("replication-read-only|pinned=$pinnedHost|plain=$plainHost")
        assertThat(pinnedHost).isEqualTo("172.30.81.10:9030")
        assertThat(plainHost).isEqualTo("172.30.81.10:9030")
    }

    @Test
    fun testPreparedScanKeepsItsPhysicalTargetAfterLogicalRoutingChanges() {
        val url = "jdbc:mysql:replication://127.0.0.1:9130,172.30.81.10:9030/"
        val owner = DorisTestSafety.connectSingle(url, properties())
        owner.isReadOnly = true
        // Only this test retains the HA owner. Production exposes only the pinned view.
        DorisReadDriver.pin(owner).use { connection ->
            val marker = "trino_statement_id=${UUID.randomUUID()}"
            val raw = connection.prepareStatement("/*$marker*/ SELECT sleep(4)")
            val captured = raw.unwrap(JdbcStatement::class.java).query.session
            owner.isReadOnly = false
            val current = owner.unwrap(JdbcConnection::class.java).session
            assertThat(captured).isNotSameAs(current)
            val executor = Executors.newSingleThreadExecutor()
            try {
                DorisPreparedStatement(raw, connection) { _, _ -> }.use { statement ->
                    val execution = executor.submit { runCatching { statement.executeQuery().close() } }
                    awaitVisible(marker)
                    assertThat(connectionId(marker)).isEqualTo(captured.threadId)
                    val start = System.nanoTime()
                    statement.cancel()
                    execution.get(2, TimeUnit.SECONDS)
                    assertThat(elapsed(start)).isLessThan(1000)
                    assertCurrentConnection(owner, current.threadId)
                }
            } finally {
                executor.shutdownNow()
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
            }
        }
        assertThat(owner.isClosed).describedAs("the original HA owner is closed with its pinned view").isTrue()
    }

    private fun executePhase(topology: String, url: String, helperFails: Boolean, concurrentClose: Boolean): Long {
        val marker = "trino_statement_id=${UUID.randomUUID()}"
        val attempted = AtomicInteger()
        val helperAttempt = CountDownLatch(1)
        val helper = resources.newCancel(DorisCancelConnectionFactory { _, _ ->
            attempted.incrementAndGet()
            helperAttempt.countDown()
            throw SQLException("R05 deliberately unavailable helper")
        }, enabled = helperFails)
        val cancelThread = AtomicReference<Thread>()
        val executor = Executors.newFixedThreadPool(3) { task -> Thread(task, "r05-worker").apply { isDaemon = true } }
        try {
            readConnection(url).use { connection ->
                val raw = connection.prepareStatement("/*$marker*/ SELECT sleep(4)")
                raw.unwrap(JdbcStatement::class.java).enableStreamingResults()
                val statement = DorisPreparedStatement(raw, connection) { answered, finished -> helper.cancel(session, marker, answered, finished) }
                val execution = executor.submit<String> {
                    runCatching { statement.executeQuery().use { result -> while (result.next()) { result.getObject(1) } } }
                        .exceptionOrNull()?.javaClass?.simpleName ?: "completed"
                }
                awaitVisible(marker)
                val started = System.nanoTime()
                val cancellation = executor.submit { cancelThread.set(Thread.currentThread()); statement.cancel() }
                val close = if (concurrentClose) executor.submit<Long> {
                    val closeStarted = System.nanoTime()
                    statement.close()
                    elapsed(closeStarted)
                } else null
                var stack = "completed within 250ms"
                try {
                    cancellation.get(250, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    stack = ManagementFactory.getThreadMXBean().getThreadInfo(requireNotNull(cancelThread.get()).threadId(), 30).toString()
                }
                cancellation.get(5, TimeUnit.SECONDS)
                val millis = elapsed(started)
                val closeMillis = close?.get(5, TimeUnit.SECONDS)
                val outcome = execution.get(5, TimeUnit.SECONDS)
                if (helperFails) assertThat(helperAttempt.await(5, TimeUnit.SECONDS)).isTrue()
                println("R05|topology=$topology|helperFails=$helperFails|concurrentClose=$concurrentClose|" +
                    "cancel_ms=$millis|close_ms=$closeMillis|attempts=${attempted.get()}|outcome=$outcome\n$stack")
                statement.close()
                assertThat(outcome).isNotEqualTo("completed")
                return maxOf(millis, closeMillis ?: 0)
            }
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun readConnection(url: String): Connection = DorisTestSafety.openSingle {
        requireNotNull(DorisReadDriver().connect(url, properties()))
    }

    private fun assertBindings(connection: Connection) {
        connection.prepareStatement("SELECT ? AS n, ? AS s").use { statement ->
            statement.setLong(1, 9007199254740993L)
            statement.setString(2, "O'Reilly 中文 😀")
            statement.executeQuery().use { result ->
                assertThat(result.next()).isTrue()
                assertThat(result.getLong(1)).isEqualTo(9007199254740993L)
                assertThat(result.getString(2)).isEqualTo("O'Reilly 中文 😀")
            }
        }
    }

    private fun statementHost(connection: Connection): String =
        connection.prepareStatement("SELECT 1").use { statement ->
            statement.executeQuery().use { result ->
                assertThat(result.next()).isTrue()
                assertThat(result.getInt(1)).isEqualTo(1)
            }
            statement.unwrap(JdbcStatement::class.java).query.session.hostInfo.hostPortPair
        }

    private fun assertCurrentConnection(owner: Connection, threadId: Long) {
        owner.createStatement().use { statement ->
            statement.executeQuery("SELECT CONNECTION_ID()").use { result ->
                assertThat(result.next()).isTrue()
                assertThat(result.getLong(1)).isEqualTo(threadId)
            }
        }
    }

    private fun properties() = DorisClientModule.connectionProperties(DorisConfig()).apply {
        setProperty("user", "root")
        setProperty("socketTimeout", "12000")
    }

    private fun elapsed(start: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

    private fun awaitVisible(marker: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (DorisTestCluster.runningDorisQueryId(marker) == null) {
            check(System.nanoTime() < deadline) { "Execution never became visible: $marker" }
            Thread.sleep(10)
        }
    }

    private fun connectionId(marker: String): Long = DorisTestCluster.openRootConnection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SHOW FULL PROCESSLIST").use { result ->
                while (result.next()) if (result.getString("Info")?.contains(marker) == true) return result.getLong("Id")
                error("Missing statement $marker")
            }
        }
    }
}
