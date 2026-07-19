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

import com.google.inject.Binder
import com.google.inject.Provides
import com.google.inject.Scopes
import com.google.inject.Singleton
import com.google.inject.multibindings.OptionalBinder.newOptionalBinder
import com.mysql.cj.jdbc.Driver
import io.airlift.configuration.AbstractConfigurationAwareModule
import io.airlift.configuration.ConfigBinder.configBinder
import io.opentelemetry.api.OpenTelemetry
import io.trino.plugin.jdbc.ConnectionFactory
import io.trino.plugin.jdbc.DecimalModule
import io.trino.plugin.jdbc.DriverConnectionFactory
import io.trino.plugin.jdbc.ForBaseJdbc
import io.trino.plugin.jdbc.JdbcClient
import io.trino.plugin.jdbc.JdbcJoinPushdownConfig
import io.trino.plugin.jdbc.JdbcJoinPushdownSessionProperties
import io.trino.plugin.jdbc.JdbcMetadataConfig
import io.trino.plugin.jdbc.JdbcModule.bindSessionPropertiesProvider
import io.trino.plugin.jdbc.QueryBuilder
import io.trino.plugin.jdbc.credential.CredentialProvider
import io.trino.spi.connector.ConnectorAccessControl
import java.util.Properties

/**
 * Base JDBC wiring for the read-only Doris connector, translated from the Trino 483
 * SingleStoreClientModule/MySqlClientModule shapes (SR K1; PLAN §4.1/§4.2).
 *
 * Deliberate omissions vs those upstream modules:
 * - The JDBC `query()` table function is NOT bound (PLAN G7.4; SR R8 must-not-port):
 *   arbitrary SQL passthrough can mutate Doris.
 * - `JdbcMetadataConfig.bulkListColumns` stays at its `false` default: bulk column listing
 *   goes through `DatabaseMetaData.getColumns`, which is proven lossy for Doris (PROBE §1,
 *   Impl #1); per-table listing routes through DorisClient's information_schema override.
 * - `DecimalModule` is installed with number-mapping UNSUPPORTED: the `MAP_TO_NUMBER`
 *   Decimal-to-DOUBLE surrogate silently loses scale on Doris DECIMAL(>38) and is
 *   explicitly rejected (ledger §A Decimal256 stance; STOCK).
 */
class DorisClientModule : AbstractConfigurationAwareModule() {
    override fun setup(binder: Binder) {
        // G3/§6.3 evidence gate: cross-check every pushdown rule's pinned evidence tuple
        // against the released brikk hazard registry BEFORE anything is bound. Any drift
        // (missing entry, changed verdict/target/provenance) refuses to construct the
        // connector — catalog creation and server startup fail loud, never silently follow
        // a changed artifact.
        DorisPushdownEvidence.verifyAgainstRegistry()

        // G7 defense in depth, layer 2: the Base JDBC client stack sits BEHIND the read-only
        // guard — every JdbcClient consumer (metadata, page source, procedures) reaches
        // DorisClient only through ReadOnlyDorisClient.
        binder.bind(DorisClient::class.java).`in`(Scopes.SINGLETON)
        binder.bind(JdbcClient::class.java).annotatedWith(ForBaseJdbc::class.java)
            .to(ReadOnlyDorisClient::class.java).`in`(Scopes.SINGLETON)
        // G7 layer 1: connector access control denies writes/DDL/procedures at the engine
        // boundary, before SQL generation (JdbcModule declares the optional binder).
        newOptionalBinder(binder, ConnectorAccessControl::class.java)
            .setBinding().to(DorisReadOnlyAccessControl::class.java).`in`(Scopes.SINGLETON)
        // Scan-path remote SQL carries the Trino query id comment (PLAN §8; DorisQueryBuilder
        // explains why the QueryBuilder seam is used instead of the RemoteQueryModifier key).
        newOptionalBinder(binder, QueryBuilder::class.java)
            .setBinding().to(DorisQueryBuilder::class.java).`in`(Scopes.SINGLETON)
        bindSessionPropertiesProvider(binder, DorisSessionProperties::class.java)
        configBinder(binder).bindConfig(DorisJdbcConfig::class.java)
        configBinder(binder).bindConfig(DorisConfig::class.java)
        install(DecimalModule.withNumberMapping(DecimalModule.MappingToNumber.UNSUPPORTED))
        // P5 join pushdown: OFF by default per plan (opt-in until a representative corpus
        // exists). This is the upstream JdbcJoinPushdownSupportModule MINUS its
        // bindConfigDefaults(joinPushdownEnabled = true) — installing the module as-is
        // would silently flip `join-pushdown.enabled` ON (proven by the suite's
        // off-by-default test). The strategy knobs remain (AUTOMATIC default — cost-aware
        // via getTableStatistics — with EAGER available).
        configBinder(binder).bindConfig(JdbcJoinPushdownConfig::class.java)
        bindSessionPropertiesProvider(binder, JdbcJoinPushdownSessionProperties::class.java)
        // Complex join pushdown (condition-as-expression) is forced OFF: our expression
        // rewriter deliberately has no variable-to-variable comparison rules (unproven
        // surface), and the complex path has NO legacy fallback at 483 — leaving it on
        // would make joins silently unpushable. The legacy path gives the per-condition
        // typed guard (DorisClient.isSupportedJoinCondition) instead.
        configBinder(binder).bindConfigDefaults(JdbcMetadataConfig::class.java) { config ->
            config.setComplexJoinPushdownEnabled(false)
        }
    }

    @Provides
    @Singleton
    @ForBaseJdbc
    fun createConnectionFactory(
        config: DorisJdbcConfig,
        dorisConfig: DorisConfig,
        credentialProvider: CredentialProvider,
        openTelemetry: OpenTelemetry,
    ): ConnectionFactory {
        // TODO(adbc): this is the seam for the future optional Arrow Flight SQL / ADBC data
        // plane (`doris.data-plane=jdbc|adbc`) — columnar result transport for large scans,
        // opt-in because Flight needs direct BE reachability (hard behind k8s/NAT). Metadata,
        // pushdown, and SQL generation stay on this JDBC plane either way. NOT scheduled;
        // see dev-docs/TODO-adbc-arrow-flight.md for the gate.
        return DriverConnectionFactory.builder(Driver(), config.connectionUrl, credentialProvider)
            .setConnectionProperties(connectionProperties(dorisConfig))
            .setOpenTelemetry(openTelemetry)
            .build()
    }

    companion object {
        /**
         * Connection property pin list per ledger §B (each entry evidence-backed):
         * - `tinyInt1isBit=false`: mandatory; the driver default reports every Doris TINYINT
         *   as BIT/Boolean (PROBE §4, Impl #7). BOOLEAN-vs-TINYINT discrimination comes from
         *   `information_schema.columns.COLUMN_TYPE`, not the protocol.
         * - `characterEncoding=UTF-8` (+`useUnicode`): unicode/CJK/emoji round-trip proven (PROBE §3).
         * - `useServerPrepStmts=false`: client prepared-statement emulation is the v1 default;
         *   both modes proved fidelity-identical, emulation is the safer default (PROBE §5).
         * - `connectTimeout`: explicit finite value (ledger §B; exact value is a config decision).
         * - `sessionVariables`: Doris session vars at SESSION scope only (PROBE §9).
         *
         * Explicitly NOT set: `rewriteBatchedStatements` (write-only optimization, SR R1) and
         * the MariaDB URL rewrite (SR R5). Result streaming is enabled per statement in
         * [DorisClient.getPreparedStatement] (`enableStreamingResults()` ==
         * `setFetchSize(Integer.MIN_VALUE)`, PROBE §7).
         */
        internal fun connectionProperties(dorisConfig: DorisConfig): Properties {
            val properties = Properties()
            properties.setProperty("tinyInt1isBit", "false")
            properties.setProperty("useUnicode", "true")
            properties.setProperty("characterEncoding", "UTF-8")
            properties.setProperty("useServerPrepStmts", "false")
            properties.setProperty("connectTimeout", dorisConfig.getConnectTimeout().toMillis().toString())
            sessionVariables(dorisConfig)?.let { properties.setProperty("sessionVariables", it) }
            return properties
        }

        private fun sessionVariables(config: DorisConfig): String? {
            val variables = buildList {
                config.getQueryTimeout()?.let { add("query_timeout=${it.roundTo(java.util.concurrent.TimeUnit.SECONDS)}") }
                config.getExecMemLimit()?.let { add("exec_mem_limit=${it.toBytes()}") }
                config.getTimeZone()?.let { timeZone ->
                    require(!timeZone.contains('\'') && !timeZone.contains(',')) {
                        "doris.time-zone must not contain quotes or commas: $timeZone"
                    }
                    add("time_zone='$timeZone'")
                }
            }
            return variables.takeIf { it.isNotEmpty() }?.joinToString(",")
        }
    }
}
