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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TestDorisPreparedStatement {
    @Test
    fun testConcurrentCloseWaitsForAbort() = assertCleanupWaitsForAbort(lateResult = false)

    @Test
    fun testLateResultCloseWaitsForAbort() = assertCleanupWaitsForAbort(lateResult = true)

    @Suppress("LongMethod", "CyclomaticComplexMethod") // Two schedules share the same strict JDBC fakes.
    private fun assertCleanupWaitsForAbort(lateResult: Boolean) {
        val executeEntered = CountDownLatch(1)
        val releaseExecute = CountDownLatch(if (lateResult) 1 else 0)
        val cancelEntered = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val cleanupReady = CountDownLatch(1)
        val aborted = AtomicBoolean()
        val statementCloses = AtomicInteger()
        val resultCloses = AtomicInteger()
        val result = jdbcProxy(ResultSet::class.java) { method ->
            check(method == "close") { "unexpected result call: $method" }
            assertThat(aborted.get()).describedAs("abort before raw ResultSet.close").isTrue()
            resultCloses.incrementAndGet()
            null
        }
        val delegate = jdbcProxy(PreparedStatement::class.java) { method ->
            when (method) {
                "executeQuery" -> {
                    executeEntered.countDown()
                    await(releaseExecute)
                    if (lateResult) cleanupReady.countDown()
                    result
                }
                "close" -> {
                    assertThat(aborted.get()).describedAs("abort before delegate.close").isTrue()
                    statementCloses.incrementAndGet()
                    null
                }
                else -> throw AssertionError("unexpected statement call: $method")
            }
        }
        val connection = jdbcProxy(Connection::class.java) { method ->
            check(method == "abort") { "unexpected connection call: $method" }
            aborted.set(true)
            null
        }
        val statement = DorisPreparedStatement(delegate, connection) {
            cancelEntered.countDown()
            await(releaseCancel)
        }
        // Daemon workers also keep a broken uninterruptible production wait from hanging the JVM.
        val executor = Executors.newFixedThreadPool(2) { task -> Thread(task).apply { isDaemon = true } }
        val futures = mutableListOf<Future<*>>()
        try {
            val execution = executor.submit<ResultSet> { statement.executeQuery() }.also { futures.add(it) }
            await(executeEntered)
            if (!lateResult) execution.get(5, SECONDS)
            val cancellation = executor.submit { statement.cancel() }.also { futures.add(it) }
            await(cancelEntered)
            val cleanup = if (lateResult) {
                releaseExecute.countDown()
                execution
            } else {
                executor.submit { cleanupReady.countDown(); statement.close() }.also { futures.add(it) }
            }
            await(cleanupReady)
            assertThatThrownBy { cleanup.get(100, MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
            assertThat(aborted.get()).isFalse()
            assertThat(statementCloses.get()).isZero()
            assertThat(resultCloses.get()).isZero()
            releaseCancel.countDown()
            cancellation.get(5, SECONDS)
            if (lateResult) {
                assertThatThrownBy { cleanup.get(5, SECONDS) }.hasCauseInstanceOf(SQLException::class.java)
                executor.submit { statement.close() }.also { futures.add(it) }.get(5, SECONDS)
            } else {
                cleanup.get(5, SECONDS)
            }
            assertThat(statementCloses.get()).isEqualTo(1)
            assertThat(resultCloses.get()).isEqualTo(if (lateResult) 1 else 0)
        } finally {
            releaseExecute.countDown()
            releaseCancel.countDown()
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, SECONDS)).describedAs("workers terminated").isTrue()
        }
    }

    @Test
    fun testDisabledHelperStillAbortsWithoutRawCancelAndCloseIsIdempotent() {
        val aborts = AtomicInteger()
        val rawCancels = AtomicInteger()
        val closes = AtomicInteger()
        val result = jdbcProxy(ResultSet::class.java) { throw AssertionError("unexpected result call: $it") }
        val delegate = jdbcProxy(PreparedStatement::class.java) { method ->
            when (method) {
                "executeQuery" -> result
                "cancel" -> { rawCancels.incrementAndGet(); null }
                "close" -> { assertThat(aborts.get()).isEqualTo(1); closes.incrementAndGet(); null }
                else -> throw AssertionError("unexpected statement call: $method")
            }
        }
        val connection = jdbcProxy(Connection::class.java) { method ->
            check(method == "abort") { "unexpected connection call: $method" }
            aborts.incrementAndGet()
            null
        }
        val statement = DorisPreparedStatement(delegate, connection) {} // Disabled helper is a no-op.
        assertThat(statement.unwrap(PreparedStatement::class.java)).isSameAs(statement)
        assertThat(statement.isWrapperFor(PreparedStatement::class.java)).isTrue()
        statement.executeQuery()
        statement.cancel()
        repeat(2) { statement.close() }
        assertThat(rawCancels.get()).isZero()
        assertThat(aborts.get()).isEqualTo(1)
        assertThat(closes.get()).isEqualTo(1)
        assertThat(statement.isClosed).isTrue()
    }

    private fun await(latch: CountDownLatch) = assertThat(latch.await(5, SECONDS)).isTrue()

    private fun <T> jdbcProxy(type: Class<T>, handler: (String) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> handler(method.name) })
}
