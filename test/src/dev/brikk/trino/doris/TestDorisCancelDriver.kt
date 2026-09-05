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

import com.mysql.cj.conf.ConnectionPropertiesTransform
import com.mysql.cj.conf.ConnectionUrl
import com.mysql.cj.conf.ConnectionUrlParser
import com.mysql.cj.conf.HostInfo
import com.mysql.cj.conf.HostsListView
import com.mysql.cj.conf.url.ReplicationDnsSrvConnectionUrl
import com.mysql.cj.exceptions.WrongArgumentException
import com.mysql.cj.jdbc.NonRegisteringDriver
import io.opentelemetry.api.OpenTelemetry
import io.trino.plugin.jdbc.credential.CredentialProvider
import io.trino.spi.security.ConnectorIdentity
import io.trino.testing.TestingConnectorSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.Properties
import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TestDorisCancelDriver {
    @Test
    fun testGlobalUrlAndConnectionPropertiesCannotOverrideLimits() {
        for (value in listOf("0", "2147483647")) {
            val url = "jdbc:mysql://fe1/?connectTimeout=$value&socketTimeout=$value" +
                "&autoReconnect=true&autoReconnectForPools=true&reconnectAtTxEnd=true&enableQueryTimeouts=true"
            for (info in listOf(null, Properties().apply { setProperty("connectTimeout", "0"); setProperty("socketTimeout", "0") })) {
                val normalDriver = NonRegisteringDriver()
                val original = normalDriver.getPropertyInfo(url, info).map { it.name to it.value }
                val driver = DorisCancelDriver(openConnection = { host ->
                    assertBounded(host)
                    connection
                })
                assertThat(driver.connect(url, info)).isSameAs(connection)
                assertThat(normalDriver.getPropertyInfo(url, info).map { it.name to it.value }).isEqualTo(original)
            }
        }
    }

    @Test
    fun testPerHostOverridesAndFailoverTryEachHostOnceAndRetainErrors() {
        val attempts = mutableListOf<String>()
        val errors = listOf(SQLException("first", "08001"), SQLException("second", "28000"))
        val driver = DorisCancelDriver(openConnection = { host ->
            assertBounded(host)
            attempts.add(host.host)
            throw errors[attempts.lastIndex]
        })
        val url = "jdbc:mysql://address=(host=fe1)(connectTimeout=0)(socketTimeout=2147483647)(autoReconnect=true)," +
            "address=(host=fe2)(connectTimeout=2147483647)(socketTimeout=0)(enableQueryTimeouts=true)/" +
            "?retriesAllDown=100&maxReconnects=100"
        val failure = assertThrows<SQLException> { driver.connect(url, Properties()) }
        assertThat(attempts).containsExactly("fe1", "fe2")
        assertThat(failure.suppressed).containsExactly(*errors.toTypedArray())
    }

    @Test
    fun testFailoverStopsAtFirstSuccess() {
        val attempts = mutableListOf<String>()
        val driver = DorisCancelDriver(openConnection = { host ->
            attempts.add(host.host)
            if (host.host == "fe1") throw SQLException("unreachable")
            connection
        })
        assertThat(driver.connect("jdbc:mysql://fe1,fe2,fe3/", null)).isSameAs(connection)
        assertThat(attempts).containsExactly("fe1", "fe2")
    }

    @Test
    fun testRemainingBudgetCapsBothTimeoutsAndStopsFurtherAttempts() {
        var now = 0L
        val attempts = mutableListOf<HostInfo>()
        val driver = DorisCancelDriver(
            openConnection = { host ->
                attempts.add(host)
                now = TimeUnit.MILLISECONDS.toNanos(DorisCancelDriver.CONNECT_BUDGET_MILLIS - 1 + attempts.lastIndex)
                throw SQLException("unreachable")
            },
            nanoTime = { now },
        )
        val failure = assertThrows<SQLException> { driver.connect("jdbc:mysql://fe1,fe2,fe3/", null) }
        assertThat(attempts.map { it.host }).containsExactly("fe1", "fe2")
        assertBounded(attempts[0])
        assertBounded(attempts[1], 1)
        assertThat(failure.suppressed).hasSize(2)
    }

    @Test
    fun testDispatchDeadlineIsNotResetWhenConnectionStarts() {
        var now = TimeUnit.SECONDS.toNanos(14)
        val attempts = mutableListOf<HostInfo>()
        val driver = DorisCancelDriver(
            openConnection = { host ->
                attempts.add(host)
                now = TimeUnit.SECONDS.toNanos(15)
                throw SQLException("unreachable")
            },
            nanoTime = { now },
            deadlineNanos = TimeUnit.SECONDS.toNanos(15),
        )
        assertThrows<SQLException> { driver.connect("jdbc:mysql://fe1,fe2/", null) }
        assertThat(attempts).hasSize(1)
        assertBounded(attempts.single(), 1000)
        assertThrows<SQLException> { driver.connect("jdbc:mysql://fe1,fe2/", null) }
        assertThat(attempts).hasSize(1) // An already expired dispatch never attempts a connection.
    }

    @Test
    fun testReplicationSrvResolvesBothRolesInsteadOfUsingUnresolvedAllView() {
        val views = mutableListOf<HostsListView>()
        val source = HostInfo(null, "fe1", 9030, "root", null, emptyMap())
        val replica = HostInfo(null, "fe2", 9031, "root", null, emptyMap())
        val url = object : ReplicationDnsSrvConnectionUrl(
            ConnectionUrlParser.parseConnectionString("jdbc:mysql+srv:replication://source-service,replica-service/"),
            Properties(),
        ) {
            override fun getHostsList(view: HostsListView): List<HostInfo> {
                views.add(view)
                return when (view) {
                    HostsListView.SOURCES -> listOf(source)
                    HostsListView.REPLICAS -> listOf(replica)
                    else -> error("ALL would return unresolved service names, not FE endpoints")
                }
            }
        }
        assertThat(DorisCancelDriver.resolvedHosts(url)).containsExactly(source, replica)
        assertThat(views).containsExactly(HostsListView.SOURCES, HostsListView.REPLICAS)
    }

    @Test
    fun testFinalPropertiesPreserveHostCredentialsTlsAndSessionSettingsWithoutMutatingCache() {
        val url = "jdbc:mysql://address=(host=fe1)(port=9030)(user=hostUser)(password=hostSecret)(sslMode=VERIFY_IDENTITY)," +
            "address=(host=fe2)(port=9031)(user=otherUser)(password=otherSecret)(sslMode=REQUIRED)/catalog" +
            "?user=globalUser&password=globalSecret&connectTimeout=0&socketTimeout=0&autoReconnect=true" +
            "&enableQueryTimeouts=true&sessionVariables=exec_mem_limit=1234&tlsVersions=TLSv1.2" +
            "&trustCertificateKeyStoreUrl=file:/truststore&trustCertificateKeyStorePassword=trustSecret" +
            "&defaultAuthenticationPlugin=mysql_native_password&useConfigs=maxPerformance"
        val info = Properties().apply { setProperty("connectionAttributes", "application:cancel-test") }
        val originalInfo = info.clone()
        val parsed = ConnectionUrl.getConnectionUrlInstance(url, info)
        val originals = parsed.hostsList.map { it.exposeAsProperties() }
        val originalGlobals = parsed.originalProperties.toMap()
        val attempts = mutableListOf<HostInfo>()
        val driver = DorisCancelDriver(openConnection = { host ->
            attempts.add(host)
            throw SQLException("inspect all hosts")
        })
        assertThrows<SQLException> { driver.connect(url, info) }
        for ((index, host) in attempts.withIndex()) {
            val expected = Properties().apply { putAll(originals[index]); putAll(forcedProperties) }
            assertThat(host.exposeAsProperties()).isEqualTo(expected)
            assertThat(host.databaseUrl).isEqualTo(url)
            assertThat(host).isNotSameAs(parsed.hostsList[index])
        }
        assertThat(attempts.map { it.user }).containsExactly("hostUser", "otherUser")
        assertThat(attempts.map { it.password }).containsExactly("hostSecret", "otherSecret")
        assertThat(attempts.map { it.getProperty("sslMode") }).containsExactly("VERIFY_IDENTITY", "REQUIRED")
        assertThat(attempts.map { it.getProperty("cachePrepStmts") }).containsOnly("true")
        assertThat(ConnectionUrl.getConnectionUrlInstance(url, info)).isSameAs(parsed)
        assertThat(parsed.hostsList.map { it.exposeAsProperties() }).isEqualTo(originals)
        assertThat(parsed.originalProperties).isEqualTo(originalGlobals)
        assertThat(info).isEqualTo(originalInfo)
    }

    @Test
    fun testTransformCannotReenableTimeoutKillsOrUnboundNetworkTimeouts() {
        val info = Properties().apply { setProperty("propertiesTransform", UnsafeTransform::class.java.name) }
        val url = "jdbc:mysql://fe1/?useConfigs=maxPerformance"
        val parsed = ConnectionUrl.getConnectionUrlInstance(url, info)
        assertThat(parsed.mainHost.getProperty("connectTimeout")).isEqualTo("0")
        val driver = DorisCancelDriver(openConnection = { host ->
            assertBounded(host)
            assertThat(host.user).isEqualTo("transformedUser")
            assertThat(host.password).isEqualTo("transformedSecret")
            assertThat(host.getProperty("sessionVariables")).isEqualTo("exec_mem_limit=5678")
            assertThat(host.getProperty("sslMode")).isEqualTo("VERIFY_IDENTITY")
            connection
        })
        assertThat(driver.connect(url, info)).isSameAs(connection)
        assertThat(parsed.mainHost.getProperty("connectTimeout")).isEqualTo("0")
        assertThat(parsed.mainHost.getProperty("enableQueryTimeouts")).isEqualTo("true")
    }

    @Test
    fun testConnectorExceptionsAreTranslatedDuringParsingAndOpening() {
        val driver = DorisCancelDriver(openConnection = { throw WrongArgumentException("bad physical property") })
        val failure = assertThrows<SQLException> { driver.connect("jdbc:mysql://fe1/", null) }
        assertThat(failure.suppressed.single()).isInstanceOf(SQLException::class.java).hasMessage("bad physical property")
        assertThat((failure.suppressed.single() as SQLException).sqlState).isEqualTo("S1009")
        assertThrows<SQLException> { driver.connect("jdbc:mysql://fe1/?useConfigs=does-not-exist", null) }
    }

    @Test
    fun testHaUrlsRetainHostsWithoutHaRetries() {
        for (scheme in listOf("loadbalance", "replication")) {
            val attempts = mutableListOf<String>()
            val driver = DorisCancelDriver(openConnection = { host ->
                assertBounded(host)
                attempts.add(host.host)
                if (host.host == "fe1") throw SQLException("unreachable")
                connection
            })
            assertThat(driver.connect("jdbc:mysql:$scheme://fe1,fe2/?retriesAllDown=100&socketTimeout=0", null)).isSameAs(connection)
            assertThat(attempts).containsExactly("fe1", "fe2")
        }
    }

    @Test
    fun testOtherProtocolsAreRejected() {
        val driver = DorisCancelDriver(openConnection = { throw AssertionError("must not open") })
        assertThat(driver.connect("jdbc:postgresql://fe1/", null)).isNull()
        for (url in listOf(
            "mysqlx://fe1/", "mysqlx+srv://service/",
        )) {
            assertThrows<SQLFeatureNotSupportedException> { driver.connect(url, null) }
        }
    }

    @Test
    fun testHelperFactoryUsesSessionCredentialsAndTimesOutStalledSql() {
        val identity = ConnectorIdentity.ofUser("cancel-test-session")
        val credentials = object : CredentialProvider {
            override fun getConnectionUser(jdbcIdentity: Optional<ConnectorIdentity>): Optional<String> {
                assertThat(jdbcIdentity).contains(identity)
                return Optional.of(DorisQueryRunner.USER)
            }

            override fun getConnectionPassword(jdbcIdentity: Optional<ConnectorIdentity>): Optional<String> = Optional.empty()
        }
        val config = DorisJdbcConfig().apply {
            setConnectionUrl("${DorisQueryRunner.JDBC_URL}/?socketTimeout=0&connectTimeout=0&autoReconnect=true")
        }
        val session = TestingConnectorSession.builder().setIdentity(identity).build()
        val factory = DorisClientModule().createCancelConnectionFactory(config, DorisConfig(), credentials, OpenTelemetry.noop())
        factory.openConnection(session, System.nanoTime() + TimeUnit.SECONDS.toNanos(15)).use { helper ->
            assertThat(helper.networkTimeout).isEqualTo(DorisCancelDriver.NETWORK_TIMEOUT_MILLIS)
            helper.createStatement().use { statement ->
                val started = System.nanoTime()
                val failure = assertThrows<SQLException> { statement.executeQuery("SELECT sleep(10)") }
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(1500L, 8000L)
                assertThat(generateSequence<Throwable>(failure) { it.cause }.toList())
                    .anyMatch { it is SocketTimeoutException }
            }
        }
    }

    @Test
    fun testTcpBlackholeTimesOutWithoutOpeningASecondConnection() {
        val loginTimeout = DriverManager.getLoginTimeout()
        val pool = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "cancel-driver-blackhole").apply { isDaemon = true } }
        try {
            ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 8000
                val started = System.nanoTime()
                val result = pool.submit<SQLException> {
                    assertThrows<SQLException> {
                        DorisCancelDriver().connect(
                            "jdbc:mysql://127.0.0.1:${server.localPort}/?connectTimeout=0&socketTimeout=0" +
                                "&autoReconnect=true&autoReconnectForPools=true&reconnectAtTxEnd=true&enableQueryTimeouts=true",
                            Properties().apply { setProperty("connectTimeout", "0"); setProperty("socketTimeout", "0") },
                        )
                    }
                }
                // Keep the accepted socket open, but never send a MySQL greeting.
                server.accept().use {
                    val failure = result.get(8, TimeUnit.SECONDS)
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    assertThat(elapsed).isBetween(1500L, 7000L)
                    assertThat(failure.suppressed).hasSize(1)
                    assertThat(generateSequence(failure.suppressed.single()) { cause -> cause.cause }.toList())
                        .anyMatch { cause -> cause is SocketTimeoutException }
                    server.soTimeout = 500
                    assertThrows<SocketTimeoutException> { server.accept().use { throw AssertionError("unexpected second connection") } }
                }
            }
        } finally {
            pool.shutdownNow()
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
        assertThat(DriverManager.getLoginTimeout()).isEqualTo(loginTimeout)
    }

    // Public no-arg class: Connector/J instantiates the configured transform reflectively.
    class UnsafeTransform : ConnectionPropertiesTransform {
        override fun transformProperties(props: Properties): Properties = props.apply {
            setProperty("connectTimeout", "0")
            setProperty("socketTimeout", "2147483647")
            setProperty("autoReconnect", "true")
            setProperty("autoReconnectForPools", "true")
            setProperty("reconnectAtTxEnd", "true")
            setProperty("enableQueryTimeouts", "true")
            setProperty("user", "transformedUser")
            setProperty("password", "transformedSecret")
            setProperty("sslMode", "VERIFY_IDENTITY")
            setProperty("sessionVariables", "exec_mem_limit=5678")
        }
    }

    companion object {
        private val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            throw AssertionError("unexpected JDBC call: ${method.name}")
        } as Connection

        private val forcedProperties = mapOf(
            "connectTimeout" to "2000", "socketTimeout" to "2000", "autoReconnect" to "false",
            "autoReconnectForPools" to "false", "reconnectAtTxEnd" to "false", "enableQueryTimeouts" to "false",
        )

        private fun assertBounded(host: HostInfo, timeout: Int = DorisCancelDriver.NETWORK_TIMEOUT_MILLIS) {
            assertThat(host.hostProperties).containsAllEntriesOf(
                forcedProperties + mapOf("connectTimeout" to timeout.toString(), "socketTimeout" to timeout.toString()),
            )
        }
    }
}
