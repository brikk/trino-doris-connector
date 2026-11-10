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
import io.trino.plugin.jdbc.JdbcMetadataFactory
import io.trino.plugin.jdbc.JdbcModule.bindSessionPropertiesProvider
import io.trino.plugin.jdbc.QueryBuilder
import io.trino.plugin.jdbc.credential.CredentialProvider
import io.trino.spi.connector.ConnectorAccessControl
import java.util.Properties

/**
 * Unlike the MySQL module, this binds no `query()` table function, which would pass arbitrary
 * SQL to Doris, and leaves `bulkListColumns` off, because bulk listing reads
 * `DatabaseMetaData.getColumns`, which misreports Doris types.
 */
class DorisClientModule : AbstractConfigurationAwareModule() {
    override fun setup(binder: Binder) {
        binder.bind(DorisClient::class.java).`in`(Scopes.SINGLETON)
        binder.bind(JdbcClient::class.java).annotatedWith(ForBaseJdbc::class.java)
            .to(ReadOnlyDorisClient::class.java).`in`(Scopes.SINGLETON)
        newOptionalBinder(binder, ConnectorAccessControl::class.java)
            .setBinding().to(DorisReadOnlyAccessControl::class.java).`in`(Scopes.SINGLETON)
        newOptionalBinder(binder, QueryBuilder::class.java)
            .setBinding().to(DorisQueryBuilder::class.java).`in`(Scopes.SINGLETON)
        bindSessionPropertiesProvider(binder, DorisSessionProperties::class.java)
        newOptionalBinder(binder, JdbcMetadataFactory::class.java)
            .setBinding().to(DorisJdbcMetadataFactory::class.java).`in`(Scopes.SINGLETON)
        configBinder(binder).bindConfig(DorisJdbcConfig::class.java)
        configBinder(binder).bindConfig(DorisConfig::class.java)
        // MAP_TO_NUMBER would read DECIMAL beyond precision 38 as DOUBLE and lose digits.
        install(DecimalModule.withNumberMapping(DecimalModule.MappingToNumber.UNSUPPORTED))
        // JdbcJoinPushdownSupportModule without its default of join-pushdown.enabled=true, which
        // would make join pushdown opt-out instead of opt-in.
        configBinder(binder).bindConfig(JdbcJoinPushdownConfig::class.java)
        bindSessionPropertiesProvider(binder, JdbcJoinPushdownSessionProperties::class.java)
        // Complex join pushdown would keep every join local: it needs variable-to-variable
        // comparison rules, which the rewriter lacks, and never falls back to the legacy path.
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
        // TODO(adbc): seam for an optional Arrow Flight SQL / ADBC result transport; parked, see
        // dev-docs/BACKLOG.md.
        return DriverConnectionFactory.builder(DorisReadDriver(), config.connectionUrl, credentialProvider)
            .setConnectionProperties(connectionProperties(dorisConfig))
            .setOpenTelemetry(openTelemetry)
            .build()
    }

    @Provides
    @Singleton
    fun createCancelConnectionFactory(
        config: DorisJdbcConfig,
        dorisConfig: DorisConfig,
        credentialProvider: CredentialProvider,
        openTelemetry: OpenTelemetry,
    ): DorisCancelConnectionFactory {
        val properties = connectionProperties(dorisConfig)
        return DorisCancelConnectionFactory { session, deadline ->
            DriverConnectionFactory.builder(DorisCancelDriver(deadlineNanos = deadline), config.connectionUrl, credentialProvider)
                .setConnectionProperties(properties)
                .setOpenTelemetry(openTelemetry)
                .build()
                .openConnection(session)
        }
    }

    companion object {
        internal fun connectionProperties(dorisConfig: DorisConfig): Properties {
            val properties = Properties()
            // Otherwise the driver reports every Doris TINYINT as BIT. BOOLEAN is recognised by its
            // COLUMN_TYPE `tinyint(1)` instead.
            properties.setProperty("tinyInt1isBit", "false")
            properties.setProperty("useUnicode", "true")
            properties.setProperty("characterEncoding", "UTF-8")
            properties.setProperty("useServerPrepStmts", "false")
            properties.setProperty("connectTimeout", DorisTimeouts.connectMillis(dorisConfig.getConnectTimeout()).toString())
            sessionVariables(dorisConfig)?.let { properties.setProperty("sessionVariables", it) }
            return properties
        }

        private fun sessionVariables(config: DorisConfig): String? {
            val variables = buildList {
                config.getQueryTimeout()?.let { add("query_timeout=${DorisTimeouts.querySeconds(it)}") }
                config.getExecMemLimit()?.let { add("exec_mem_limit=${it.toBytes()}") }
                config.getTimeZone()?.let { add("time_zone='$it'") }
            }
            return variables.takeIf { it.isNotEmpty() }?.joinToString(",")
        }
    }
}
