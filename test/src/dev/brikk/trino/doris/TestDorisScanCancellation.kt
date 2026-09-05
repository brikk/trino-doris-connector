/*
 * Copyright 2026 Brikk
 *
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

import io.trino.plugin.base.mapping.DefaultIdentifierMapping
import io.trino.plugin.jdbc.BaseJdbcConfig
import io.trino.plugin.jdbc.ConnectionFactory
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcMetadataConfig
import io.trino.plugin.jdbc.JdbcMetadataSessionProperties
import io.trino.plugin.jdbc.JdbcPageSource
import io.trino.plugin.jdbc.JdbcQueryRelationHandle
import io.trino.plugin.jdbc.JdbcSplit
import io.trino.plugin.jdbc.JdbcTableHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.plugin.jdbc.RemoteTableName
import io.trino.plugin.jdbc.PreparedQuery
import io.trino.plugin.jdbc.logging.RemoteQueryModifier
import io.trino.spi.connector.ColumnHandle
import io.trino.spi.connector.SchemaTableName
import io.trino.spi.predicate.Domain
import io.trino.spi.predicate.TupleDomain
import io.trino.spi.type.BigintType.BIGINT
import io.trino.testing.TestingConnectorSession
import io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.util.Optional
import java.util.OptionalLong
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Trino 483 page-source lifecycle with controlled JDBC races and a live execute-phase proof. */
class TestDorisScanCancellation {
    @Test
    fun testLivePageSourceCloseCancelsBeforeFirstResult() {
        val config = DorisConfig()
        val session = TestingConnectorSession.builder().setPropertyMetadata(
            DorisSessionProperties(config).sessionProperties +
                JdbcMetadataSessionProperties(JdbcMetadataConfig(), Optional.empty()).sessionProperties,
        ).build()
        val client = DorisClient(
            BaseJdbcConfig(), config,
            ConnectionFactory {
                DriverManager.getConnection("${DorisQueryRunner.JDBC_URL}/?user=root&connectTimeout=5000&socketTimeout=40000")
            },
            DorisQueryBuilder(RemoteQueryModifier.NONE), TESTING_TYPE_MANAGER,
            DefaultIdentifierMapping(), RemoteQueryModifier.NONE,
            DorisCancelConnectionFactory { _, deadline ->
                DorisCancelDriver(deadlineNanos = deadline).connect("${DorisQueryRunner.JDBC_URL}/?user=root", null)!!
            },
        )
        val table = JdbcTableHandle(
            JdbcQueryRelationHandle(PreparedQuery("SELECT sleep(30) AS value", emptyList())),
            TupleDomain.all(), emptyList(), Optional.empty(), OptionalLong.empty(),
            Optional.of(listOf(COLUMN)), Optional.empty(), 0, Optional.empty(), emptyList(),
        )
        val marker = DorisRemoteQueryModifier.marker(session.queryId)
        val executor = Executors.newSingleThreadExecutor()
        val closer = Executors.newSingleThreadExecutor()
        try {
            JdbcPageSource(client, executor, session, SPLIT, table, listOf(COLUMN)).use { source ->
                assertThat(source.nextSourcePage).isNull()
                awaitCondition("live execute-phase scan appears") { DorisTestCluster.runningStatements().any { it.contains(marker) } }
                assertThat(source.isBlocked.isDone).describedAs("no result produced yet").isFalse()
                val dispatched = DorisClusterScopedCancel.killsDispatched.get()
                closer.submit { source.close() }.get(TIMEOUT_SECONDS, SECONDS)
                assertThat(DorisClusterScopedCancel.killsDispatched.get()).isEqualTo(dispatched + 1)
                source.isBlocked.handle { _, _ -> null }.get(TIMEOUT_SECONDS, SECONDS)
                assertThat(source.isBlocked.isCompletedExceptionally).isTrue()
                awaitCondition("live Doris statement released") { DorisTestCluster.runningStatements().none { it.contains(marker) } }
            }
        } finally {
            executor.shutdownNow()
            closer.shutdownNow()
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, SECONDS)).isTrue()
            assertThat(closer.awaitTermination(TIMEOUT_SECONDS, SECONDS)).isTrue()
        }
    }

    @Test
    fun testCloseAbortsBlockedExecuteWithoutDriverCancel() {
        FakeJdbc().use { jdbc ->
            val scan = jdbc.scan()
            val source = jdbc.pageSource(scan)
            assertThat(source.nextSourcePage).isNull()
            await(scan.executeEntered, "executeQuery entered")
            val execution = source.isBlocked
            assertThat(execution.isDone).isFalse()
            val dispatched = DorisClusterScopedCancel.killsDispatched.get()

            jdbc.closeSource(source)

            assertThat(scan.rawCancels.get()).isZero()
            assertThat(scan.aborts.get()).isPositive()
            assertThat(DorisClusterScopedCancel.killsDispatched.get()).isEqualTo(dispatched + 1)
            // Connection.abort, not raw cancel/close or the helper's KILL, releases executeQuery.
            execution.handle { _, _ -> null }.get(TIMEOUT_SECONDS, SECONDS)
            assertThat(execution.isCompletedExceptionally).isTrue()
            jdbc.awaitKill(scan)
            scan.assertClosedAfterAbort()
        }
    }

    @Test
    fun testClosePreventsQueuedExecutionFromReachingDriver() {
        FakeJdbc().use { jdbc ->
            val occupied = CountDownLatch(1)
            val releaseExecutor = CountDownLatch(1)
            val blocker = jdbc.executor.submit {
                occupied.countDown()
                await(releaseExecutor, "executor released")
            }
            try {
                await(occupied, "executor occupied")
                val scan = jdbc.scan()
                val source = jdbc.pageSource(scan)
                assertThat(source.nextSourcePage).isNull()
                val execution = source.isBlocked
                assertThat(execution.isDone).isFalse()
                assertThat(scan.rawExecutes.get()).isZero()

                jdbc.closeSource(source)
                releaseExecutor.countDown()
                blocker.get(TIMEOUT_SECONDS, SECONDS)
                // A FIFO barrier proves the queued supplier actually had a chance to run.
                jdbc.executor.submit {}.get(TIMEOUT_SECONDS, SECONDS)
                execution.handle { _, _ -> null }.get(TIMEOUT_SECONDS, SECONDS)

                assertThat(scan.rawExecutes.get()).isZero()
                assertThat(scan.rawCancels.get()).isZero()
                assertThat(execution.isCompletedExceptionally).isTrue()
                assertThat(scan.statementClosed.get()).isTrue()
                assertThat(scan.connectionClosed.get()).isTrue()
            } finally {
                releaseExecutor.countDown()
            }
        }
    }

    @Test
    fun testCloseCleansResultProducedBeforePageSourceAdoptsIt() {
        FakeJdbc().use { jdbc ->
            val scan = jdbc.scan()
            val source = jdbc.pageSource(scan)
            // Keep execute gated until this call returns, so it cannot adopt the result inline.
            assertThat(source.nextSourcePage).isNull()
            await(scan.executeEntered, "executeQuery entered")
            scan.releaseExecute.countDown()
            source.isBlocked.get(TIMEOUT_SECONDS, SECONDS)
            assertThat(scan.resultProduced.get()).isTrue()
            assertThat(scan.nextCalls.get()).isZero()

            jdbc.closeSource(source)

            assertThat(scan.rawCancels.get()).isZero()
            assertThat(scan.aborts.get()).isPositive()
            assertThat(scan.resultClosed.get()).isTrue()
            assertThat(scan.nextCalls.get()).isZero()
            jdbc.awaitKill(scan)
            scan.assertClosedAfterAbort()
        }
    }

    @Test
    fun testEarlyCloseKillsOnlyItsStatementNotSiblingWithSameQueryId() {
        FakeJdbc().use { jdbc ->
            val first = jdbc.scan()
            val sibling = jdbc.scan()
            val firstStatement = jdbc.buildSql(first)
            val siblingStatement = jdbc.buildSql(sibling)
            val marker = statementMarker(first.sql)
            val siblingMarker = statementMarker(sibling.sql)
            assertThat(marker).isNotEqualTo(siblingMarker)
            val queryMarker = "/*trino_query_id=${jdbc.session.queryId}*/"
            assertThat(first.sql).contains(queryMarker).doesNotContain(siblingMarker)
            assertThat(sibling.sql).contains(queryMarker).doesNotContain(marker)
            // A bound predicate also pins parameter preservation across transformQuery.
            assertThat(first.sql).contains("`value` = ?")
            assertThat(first.parameters).containsExactly(1 to 7L)
            assertThat(sibling.parameters).containsExactly(1 to 7L)

            first.releaseExecute.countDown()
            sibling.releaseExecute.countDown()
            firstStatement.executeQuery().use { firstResult ->
                siblingStatement.executeQuery().use { siblingResult ->
                    assertThat(firstResult.next()).isTrue()
                    assertThat(firstResult.getLong(1)).isEqualTo(7L)
                    assertThat(firstResult.isAfterLast).isFalse()
                    assertThat(siblingResult.next()).isTrue()
                    // Both streams still have a second row; closing just one is not query cancellation.
                    jdbc.closer.submit { firstStatement.close() }.get(TIMEOUT_SECONDS, SECONDS)
                    jdbc.awaitKill(first)

                    assertThat(jdbc.killedQueryIds).containsExactly(first.queryId)
                    assertThat(first.nextCalls.get()).isEqualTo(1)
                    assertThat(sibling.running.get()).isTrue()
                    assertThat(sibling.aborts.get()).isZero()
                    assertThat(sibling.resultClosed.get()).isFalse()
                    assertThat(siblingResult.next()).isTrue()
                    assertThat(siblingResult.getLong(1)).isEqualTo(7L)
                    assertThat(siblingResult.next()).isFalse()
                    siblingStatement.close()
                }
            }
            assertThat(first.rawCancels.get()).isZero()
            assertThat(sibling.rawCancels.get()).isZero()
            assertThat(jdbc.killedQueryIds).containsExactly(first.queryId)
            first.assertClosedAfterAbort()
        }
    }

    @Test
    fun testFullyConsumedPageSourceDoesNotDispatchCancellation() {
        FakeJdbc().use { jdbc ->
            val scan = jdbc.scan()
            val source = jdbc.pageSource(scan)
            assertThat(source.nextSourcePage).isNull()
            await(scan.executeEntered, "executeQuery entered")
            scan.releaseExecute.countDown()
            source.isBlocked.get(TIMEOUT_SECONDS, SECONDS)
            val page = requireNotNull(source.nextSourcePage)
            assertThat(page.positionCount).isEqualTo(2)
            assertThat(BIGINT.getLong(page.getBlock(0), 0)).isEqualTo(7L)
            assertThat(source.isFinished).isTrue()
            assertThat(scan.result.isAfterLast).isTrue()
            val dispatched = DorisClusterScopedCancel.killsDispatched.get()

            jdbc.closeSource(source)
            jdbc.closeSource(source)

            // Dispatch counting is synchronous, unlike a racy absence-of-helper assertion.
            assertThat(DorisClusterScopedCancel.killsDispatched.get()).isEqualTo(dispatched)
            assertThat(scan.rawCancels.get()).isZero()
            assertThat(scan.aborts.get()).isZero()
            assertThat(scan.resultClosed.get()).isTrue()
            assertThat(scan.statementClosed.get()).isTrue()
            assertThat(scan.connectionClosed.get()).isTrue()
        }
    }

    @Test
    fun testCancelledOrClosedStatementCannotExecute() {
        for (cancelOnly in listOf(true, false)) {
            FakeJdbc().use { jdbc ->
                val scan = jdbc.scan()
                val statement = jdbc.buildSql(scan)
                if (cancelOnly) statement.cancel() else statement.close()
                scan.releaseExecute.countDown()
                assertThatThrownBy { statement.executeQuery() }.isInstanceOf(SQLException::class.java)
                assertThat(scan.rawExecutes.get()).isZero()
                assertThat(scan.rawCancels.get()).isZero()
            }
        }
    }

    private class FakeJdbc : AutoCloseable {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "scan-test-execute").apply { isDaemon = true } }
        val closer = Executors.newSingleThreadExecutor { Thread(it, "scan-test-close").apply { isDaemon = true } }
        private val config = DorisConfig().setClusterScopedCancel(true)
        val session = TestingConnectorSession.builder().setPropertyMetadata(
            DorisSessionProperties(config).sessionProperties +
                JdbcMetadataSessionProperties(JdbcMetadataConfig(), Optional.empty()).sessionProperties,
        ).build()
        private val scans = CopyOnWriteArrayList<FakeScan>()
        private val pendingConnections = ConcurrentLinkedQueue<Connection>()
        private val sources = mutableListOf<JdbcPageSource>()
        private val statements = mutableListOf<PreparedStatement>()
        val killedQueryIds = CopyOnWriteArrayList<String>()
        private val helperClosed = CountDownLatch(1)
        private val helperSql = CopyOnWriteArrayList<String>()
        private val client = DorisClient(
            BaseJdbcConfig(), config,
            ConnectionFactory { pendingConnections.poll() ?: helperConnection() },
            DorisQueryBuilder(RemoteQueryModifier.NONE), TESTING_TYPE_MANAGER,
            DefaultIdentifierMapping(), RemoteQueryModifier.NONE,
            DorisCancelConnectionFactory { _, _ -> helperConnection() },
        )

        fun scan(): FakeScan = FakeScan("a-${scans.size + 1}").also { scans.add(it) }

        fun pageSource(scan: FakeScan): JdbcPageSource {
            pendingConnections.add(scan.connection)
            return JdbcPageSource(client, executor, session, SPLIT, TABLE, listOf(COLUMN)).also { sources.add(it) }
        }

        fun buildSql(scan: FakeScan): PreparedStatement =
            client.buildSql(session, scan.connection, SPLIT, TABLE, listOf(COLUMN)).also { statements.add(it) }

        fun closeSource(source: JdbcPageSource) {
            closer.submit { source.close() }.get(TIMEOUT_SECONDS, SECONDS)
        }

        fun awaitKill(scan: FakeScan) {
            await(helperClosed, "cancel helper closed")
            assertThat(helperSql).contains("SET fetch_all_fe_for_system_table = true", "SHOW FULL PROCESSLIST")
            assertThat(killedQueryIds).containsExactly(scan.queryId)
            assertThat(scan.running.get()).isFalse()
        }

        private fun helperConnection(): Connection = jdbcProxy(Connection::class.java) { method, _ ->
            when (method) {
                "createStatement" -> jdbcProxy(Statement::class.java) { operation, args ->
                    when (operation) {
                        "execute" -> {
                            val sql = args[0] as String
                            helperSql.add(sql)
                            if (sql.startsWith("KILL QUERY ")) {
                                val queryId = sql.removePrefix("KILL QUERY ").removeSurrounding("\"")
                                killedQueryIds.add(queryId)
                                scans.single { it.queryId == queryId }.running.set(false)
                            } else {
                                assertThat(sql).isEqualTo("SET fetch_all_fe_for_system_table = true")
                            }
                            false
                        }
                        "executeQuery" -> {
                            assertThat(args[0]).isEqualTo("SHOW FULL PROCESSLIST")
                            helperSql.add(args[0] as String)
                            processlist()
                        }
                        "close", "setQueryTimeout" -> null
                        else -> throw AssertionError("Unexpected helper statement call: $operation")
                    }
                }
                "close" -> { helperClosed.countDown(); null }
                "setNetworkTimeout" -> null
                else -> throw AssertionError("Unexpected helper connection call: $method")
            }
        }

        private fun processlist(): ResultSet {
            // Socket abort deliberately leaves a visible server row until KILL, as on a slow FE.
            val rows = scans.filter { it.running.get() }
            var index = -1
            return jdbcProxy(ResultSet::class.java) { method, args ->
                when (method) {
                    "next" -> ++index < rows.size
                    "getString" -> when (args[0]) {
                        "Info" -> rows[index].sql
                        "Command" -> "Query"
                        "QueryId" -> rows[index].queryId
                        else -> throw AssertionError("Unexpected processlist column: ${args[0]}")
                    }
                    "close" -> null
                    else -> throw AssertionError("Unexpected processlist call: $method")
                }
            }
        }

        override fun close() {
            try {
                // Release gates even when a regression makes an assertion fail before cancellation.
                scans.forEach { it.releaseExecute.countDown() }
                closer.submit {
                    sources.forEach { it.close() }
                    statements.forEach { it.close() }
                    scans.forEach { it.connection.close() }
                }.get(TIMEOUT_SECONDS, SECONDS)
            } finally {
                executor.shutdownNow()
                closer.shutdownNow()
                assertThat(executor.awaitTermination(TIMEOUT_SECONDS, SECONDS)).isTrue()
                assertThat(closer.awaitTermination(TIMEOUT_SECONDS, SECONDS)).isTrue()
            }
        }
    }

    private class FakeScan(val queryId: String) {
        lateinit var sql: String
        val parameters = mutableListOf<Pair<Int, Long>>()
        val executeEntered = CountDownLatch(1)
        val releaseExecute = CountDownLatch(1)
        val rawExecutes = AtomicInteger()
        val rawCancels = AtomicInteger()
        val aborts = AtomicInteger()
        val nextCalls = AtomicInteger()
        val running = AtomicBoolean()
        val resultProduced = AtomicBoolean()
        val resultClosed = AtomicBoolean()
        val statementClosed = AtomicBoolean()
        val connectionClosed = AtomicBoolean()
        private val events = CopyOnWriteArrayList<String>()
        val result: ResultSet = jdbcProxy(ResultSet::class.java) { method, _ ->
            when (method) {
                "next" -> {
                    check(!resultClosed.get()) { "Reading a closed result" }
                    (nextCalls.incrementAndGet() <= 2).also { if (!it) running.set(false) }
                }
                "getLong", "getObject" -> 7L
                "wasNull" -> false
                "isAfterLast" -> nextCalls.get() > 2
                "isClosed" -> resultClosed.get()
                "close" -> { resultClosed.set(true); null }
                else -> throw AssertionError("Unexpected scan result call: $method")
            }
        }
        private val rawStatement: PreparedStatement = jdbcProxy(PreparedStatement::class.java) { method, args ->
            when (method) {
                "executeQuery" -> {
                    rawExecutes.incrementAndGet()
                    if (statementClosed.get() || connectionClosed.get()) throw SQLException("JDBC statement closed")
                    running.set(true)
                    executeEntered.countDown()
                    await(releaseExecute, "executeQuery released by abort or test")
                    if (aborts.get() > 0) throw SQLException("JDBC connection aborted")
                    resultProduced.set(true)
                    result
                }
                "cancel" -> { rawCancels.incrementAndGet(); null }
                "close" -> {
                    events.add("statement.close")
                    statementClosed.set(true)
                    if (resultProduced.get()) result.close()
                    null
                }
                "setLong" -> { parameters.add((args[0] as Int) to (args[1] as Long)); null }
                "isWrapperFor" -> false
                "isClosed" -> statementClosed.get()
                "getResultSet" -> if (resultProduced.get()) result else null
                "getConnection" -> connection
                else -> throw AssertionError("Unexpected raw statement call: $method")
            }
        }
        val connection: Connection = jdbcProxy(Connection::class.java) { method, args ->
            when (method) {
                "prepareStatement" -> { sql = args[0] as String; rawStatement }
                "setReadOnly" -> null
                "isClosed" -> connectionClosed.get()
                "abort" -> {
                    events.add("connection.abort")
                    aborts.incrementAndGet()
                    connectionClosed.set(true)
                    releaseExecute.countDown()
                    null
                }
                "close" -> { connectionClosed.set(true); null }
                else -> throw AssertionError("Unexpected scan connection call: $method")
            }
        }

        fun assertClosedAfterAbort() {
            assertThat(rawCancels.get()).isZero()
            assertThat(statementClosed.get()).isTrue()
            assertThat(connectionClosed.get()).isTrue()
            assertThat(events).contains("connection.abort", "statement.close")
            assertThat(events.indexOf("connection.abort")).isLessThan(events.indexOf("statement.close"))
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 10L
        private val COLUMN = JdbcColumnHandle(
            "value",
            JdbcTypeHandle(Types.BIGINT, Optional.of("bigint"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            BIGINT,
        )
        private val TABLE = JdbcTableHandle(
            SchemaTableName("test", "scan"),
            RemoteTableName(Optional.of("test"), Optional.empty(), "scan"),
            Optional.empty(),
        ).intersectedWithConstraint(TupleDomain.withColumnDomains(mapOf<ColumnHandle, Domain>(COLUMN to Domain.singleValue(BIGINT, 7L))))
        private val SPLIT = JdbcSplit(Optional.empty())

        private fun statementMarker(sql: String): String {
            val matches = Regex("/\\*trino_statement_id=([0-9a-f-]+)\\*/").findAll(sql).toList()
            assertThat(matches).hasSize(1)
            val match = matches.single()
            assertThat(UUID.fromString(match.groupValues[1]).toString()).isEqualTo(match.groupValues[1])
            return match.value
        }

        private fun await(latch: CountDownLatch, description: String) {
            assertThat(latch.await(TIMEOUT_SECONDS, SECONDS)).describedAs(description).isTrue()
        }

        private fun awaitCondition(description: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + SECONDS.toNanos(TIMEOUT_SECONDS)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "Timed out: $description" }
                Thread.sleep(100)
            }
        }

        private fun <T> jdbcProxy(type: Class<T>, handler: (String, Array<out Any?>) -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "Fake${type.simpleName}"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    else -> handler(method.name, args ?: emptyArray())
                }
            })
    }
}
