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

import com.google.common.base.Ticker
import io.airlift.units.Duration
import io.trino.plugin.base.cache.identity.SingletonIdentityCacheMapping
import io.trino.plugin.jdbc.CachingJdbcClient
import io.trino.plugin.jdbc.JdbcClient
import io.trino.plugin.jdbc.JdbcTableHandle
import io.trino.plugin.jdbc.JdbcTransactionHandle
import io.trino.plugin.jdbc.RemoteTableName
import io.trino.plugin.jdbc.TimestampTimeZoneDomain
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.connector.SchemaTableName
import io.trino.spi.statistics.Estimate
import io.trino.spi.statistics.TableStatistics
import io.trino.testing.TestingConnectorSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger

class TestDorisStatisticsPolicy {
    @Test
    fun testDisabledReadsAndFillsBypassBothRealCachingLayers() {
        for (cacheMissing in listOf(false, true)) {
            val reads = AtomicInteger()
            val known = TableStatistics.builder().setRowCount(Estimate.of(1.0)).build()
            val delegate = Proxy.newProxyInstance(JdbcClient::class.java.classLoader, arrayOf(JdbcClient::class.java)) { _, method, args ->
                check(method.name == "getTableStatistics") { "Unexpected delegate call: ${method.name}" }
                check(DorisSessionProperties.isStatisticsEnabled(args[0] as ConnectorSession))
                reads.incrementAndGet()
                known
            } as JdbcClient
            val ttl = Duration.valueOf("5m")
            val identity = SingletonIdentityCacheMapping()
            val global = CachingJdbcClient(Ticker.systemTicker(), delegate, emptySet(), identity, ttl, ttl, ttl, ttl, cacheMissing, 1000)
            val factory = DorisJdbcMetadataFactory(global, TimestampTimeZoneDomain.ANY, emptySet(), identity)
            val enabled = session(true)
            val disabled = session(false)
            val first = factory.create(JdbcTransactionHandle())
            val a = table("a")
            assertThat(first.getTableStatistics(enabled, a)).isEqualTo(known)
            assertThat(first.getTableStatistics(disabled, a)).isEqualTo(TableStatistics.empty())
            assertThat(first.getTableStatistics(enabled, a)).isEqualTo(known)
            val next = factory.create(JdbcTransactionHandle())
            assertThat(next.getTableStatistics(disabled, a)).isEqualTo(TableStatistics.empty())
            assertThat(next.getTableStatistics(enabled, a)).isEqualTo(known)
            assertThat(reads.get()).isEqualTo(1) // real shared cache still serves enabled hits
            val b = table("b")
            assertThat(next.getTableStatistics(disabled, b)).isEqualTo(TableStatistics.empty())
            assertThat(reads.get()).isEqualTo(1) // no delegate/cache fill from disabled sessions
            assertThat(next.getTableStatistics(enabled, b)).isEqualTo(known)
            assertThat(reads.get()).isEqualTo(2)
            assertThat(factory.create(JdbcTransactionHandle()).getTableStatistics(enabled, b)).isEqualTo(known)
            assertThat(reads.get()).isEqualTo(2)
        }
    }

    private fun session(enabled: Boolean) = TestingConnectorSession.builder()
        .setPropertyMetadata(DorisSessionProperties(DorisConfig()).sessionProperties)
        .setPropertyValues(mapOf("statistics_enabled" to enabled)).build()

    private fun table(name: String) = JdbcTableHandle(SchemaTableName("stats", name),
        RemoteTableName(Optional.of("stats"), Optional.empty(), name), Optional.empty())
}
