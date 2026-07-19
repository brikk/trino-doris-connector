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
import io.airlift.units.Duration
import io.trino.plugin.base.session.PropertyMetadataUtil.durationProperty
import io.trino.plugin.base.session.SessionPropertiesProvider
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.session.PropertyMetadata

/**
 * Safe per-query session controls (PLAN §4.3). `query_timeout` is re-homed here as a
 * first-class read-path property — StarRocks sourced it from a *write* session-properties
 * class, explicitly rejected (SR K11/R2; ledger §D). Applied SERVER-side (`SET query_timeout`)
 * on each scan connection in [DorisClient.buildSql]: live-probed as the only mechanism that
 * bounds a streaming scan end-to-end, including the send-blocked phase (the client-side
 * `Statement.setQueryTimeout` timer does not cover result draining). The catalog-level default
 * is [DorisConfig.getQueryTimeout], which is also applied via `sessionVariables` at connection
 * open.
 */
class DorisSessionProperties @Inject constructor(dorisConfig: DorisConfig) : SessionPropertiesProvider {
    private val properties: List<PropertyMetadata<*>> = listOf(
        durationProperty(
            QUERY_TIMEOUT,
            "Per-statement query timeout enforced via JDBC (KILL QUERY on expiry); unset means no client-side limit",
            dorisConfig.getQueryTimeout(),
            false,
        ),
    )

    override fun getSessionProperties(): List<PropertyMetadata<*>> = properties

    companion object {
        const val QUERY_TIMEOUT = "query_timeout"

        fun getQueryTimeout(session: ConnectorSession): Duration? =
            session.getProperty(QUERY_TIMEOUT, Duration::class.java)
    }
}
