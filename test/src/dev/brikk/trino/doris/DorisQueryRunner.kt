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

import com.google.common.collect.ImmutableMap
import io.airlift.log.Logger
import io.airlift.log.Logging
import io.airlift.testing.Closeables.closeAllSuppress
import io.trino.testing.DistributedQueryRunner
import io.trino.testing.TestingSession.testSessionBuilder
import java.sql.Connection
import java.sql.DriverManager

/**
 * Query-runner bootstrap against the ALREADY-RUNNING stock Doris 4.1.3 compose cluster
 * (`jvm/trino-doris/compose`, FE MySQL host port 9130, root / no password). Mirrors the
 * DucklakeQueryRunner conventions. If the cluster is down: `jvm/trino-doris/compose/up.sh`.
 */
object DorisQueryRunner {
    const val CATALOG = "doris"
    const val JDBC_URL = "jdbc:mysql://127.0.0.1:9130"
    const val USER = "root"

    @JvmStatic
    fun builder(): Builder = Builder(createDorisCatalog = true)

    /**
     * Runner for dynamic-catalog tests: the Doris PLUGIN is installed but NO static `doris`
     * catalog is created and the default session has no catalog — catalogs are created by
     * the test itself via `CREATE CATALOG ... USING doris`. TestingTrinoServer runs with
     * dynamic catalog management + in-memory catalog store by default at 483 (the upstream
     * `TestDynamicCatalogs` recipe), so no extra coordinator properties are needed.
     */
    @JvmStatic
    fun dynamicCatalogBuilder(): Builder = Builder(createDorisCatalog = false)

    /** Opens a direct JDBC connection to the Doris FE for fixture setup / oracle queries. */
    fun openDirectConnection(): Connection = DriverManager.getConnection("$JDBC_URL/?user=$USER")

    class Builder internal constructor(
        private val createDorisCatalog: Boolean,
    ) : DistributedQueryRunner.Builder<Builder>(
        testSessionBuilder()
            .apply {
                if (createDorisCatalog) {
                    setCatalog(CATALOG)
                    setSchema("p0_probe")
                }
            }
            .build(),
    ) {
        private val connectorProperties: ImmutableMap.Builder<String, String> = ImmutableMap.builder()

        fun addConnectorProperty(key: String, value: String): Builder {
            connectorProperties.put(key, value)
            return self()
        }

        @Throws(Exception::class)
        override fun build(): DistributedQueryRunner {
            val queryRunner: DistributedQueryRunner = super.build()
            try {
                queryRunner.installPlugin(DorisPlugin())
                if (createDorisCatalog) {
                    val properties = ImmutableMap.builder<String, String>()
                        .put("connection-url", JDBC_URL)
                        .put("connection-user", USER)
                        .putAll(connectorProperties.buildOrThrow())
                        .buildKeepingLast()
                    queryRunner.createCatalog(CATALOG, "doris", properties)
                }
                return queryRunner
            } catch (e: Throwable) {
                closeAllSuppress(e, queryRunner)
                throw e
            }
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun main() {
        Logging.initialize()
        val queryRunner = builder()
            .addCoordinatorProperty("http-server.http.port", "8080")
            .build()
        val log = Logger.get(DorisQueryRunner::class.java)
        log.info("======== SERVER STARTED ========")
        log.info("\n====\n%s\n====", queryRunner.coordinator.baseUrl)
    }
}
