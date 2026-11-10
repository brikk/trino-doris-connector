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

import com.google.inject.Inject
import io.trino.plugin.base.cache.identity.IdentityCacheMapping
import io.trino.plugin.jdbc.DefaultJdbcMetadata
import io.trino.plugin.jdbc.DefaultJdbcMetadataFactory
import io.trino.plugin.jdbc.JdbcClient
import io.trino.plugin.jdbc.JdbcMetadata
import io.trino.plugin.jdbc.JdbcQueryEventListener
import io.trino.plugin.jdbc.TimestampTimeZoneDomain
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.connector.ConnectorTableHandle
import io.trino.spi.statistics.TableStatistics

/** Checks `statistics_enabled` ahead of the shared and per-transaction caches, which would otherwise answer first. */
class DorisJdbcMetadataFactory @Inject constructor(
    jdbcClient: JdbcClient,
    private val timestampDomain: TimestampTimeZoneDomain,
    private val listeners: Set<@JvmSuppressWildcards JdbcQueryEventListener>,
    identityMapping: IdentityCacheMapping,
) : DefaultJdbcMetadataFactory(jdbcClient, timestampDomain, listeners, identityMapping) {
    override fun create(transactionCachingJdbcClient: JdbcClient): JdbcMetadata {
        return DorisJdbcMetadata(transactionCachingJdbcClient, timestampDomain, listeners)
    }

    private class DorisJdbcMetadata(
        jdbcClient: JdbcClient,
        timestampDomain: TimestampTimeZoneDomain,
        listeners: Set<JdbcQueryEventListener>,
    ) : DefaultJdbcMetadata(jdbcClient, timestampDomain, PRECALCULATE_STATISTICS_FOR_PUSHDOWN, listeners) {
        override fun getTableStatistics(session: ConnectorSession, tableHandle: ConnectorTableHandle): TableStatistics {
            if (!DorisSessionProperties.isStatisticsEnabled(session)) {
                return TableStatistics.empty()
            }
            return super.getTableStatistics(session, tableHandle)
        }
    }

    companion object {
        // Same value DefaultJdbcMetadataFactory passes.
        private const val PRECALCULATE_STATISTICS_FOR_PUSHDOWN = true
    }
}
