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
package dev.brikk.doris.trino.plugin

import io.airlift.units.DataSize
import io.airlift.units.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class TestDorisJdbcConfig {
    @Test
    fun testValidUrl() {
        val config = DorisJdbcConfig().also { it.setConnectionUrl("jdbc:mysql://doris-fe:9030") }
        assertThat(config.isUrlValid()).isTrue()
        assertThat(config.isUrlWithoutDatabase()).isTrue()
    }

    @Test
    fun testMultiHostCommaListUrl() {
        // Ledger §B multi-host: plain comma-list is the accepted Connector/J failover syntax.
        val config = DorisJdbcConfig().also { it.setConnectionUrl("jdbc:mysql://fe1:9030,fe2:9030") }
        assertThat(config.isUrlValid()).isTrue()
        assertThat(config.isUrlWithoutDatabase()).isTrue()
    }

    @Test
    fun testNonMySqlUrlRejected() {
        val config = DorisJdbcConfig().also { it.setConnectionUrl("jdbc:postgresql://doris-fe:9030") }
        assertThat(config.isUrlValid()).isFalse()
    }

    @Test
    fun testUrlWithDatabaseRejected() {
        // Catalog-as-schema (Doris db -> Trino schema) requires connecting without a default
        // database (ledger §B; SR K3).
        val config = DorisJdbcConfig().also { it.setConnectionUrl("jdbc:mysql://doris-fe:9030/somedb") }
        assertThat(config.isUrlWithoutDatabase()).isFalse()
    }

    @Test
    fun testConnectionPropertiesPinList() {
        // Ledger §B pin list.
        val properties = DorisClientModule.connectionProperties(DorisConfig())
        assertThat(properties.getProperty("tinyInt1isBit")).isEqualTo("false")
        assertThat(properties.getProperty("characterEncoding")).isEqualTo("UTF-8")
        assertThat(properties.getProperty("useUnicode")).isEqualTo("true")
        assertThat(properties.getProperty("useServerPrepStmts")).isEqualTo("false")
        assertThat(properties.getProperty("connectTimeout")).isEqualTo("10000")
        // Rejected StarRocks prior art must not sneak back in (SR R1).
        assertThat(properties.getProperty("rewriteBatchedStatements")).isNull()
        // No session variables unless configured.
        assertThat(properties.getProperty("sessionVariables")).isNull()
    }

    @Test
    fun testSessionVariablesAppliedAtSessionScope() {
        val config = DorisConfig()
            .setQueryTimeout(Duration(123.0, TimeUnit.SECONDS))
            .setExecMemLimit(DataSize.ofBytes(2147483648))
            .setTimeZone("Etc/UTC")
        val properties = DorisClientModule.connectionProperties(config)
        assertThat(properties.getProperty("sessionVariables"))
            .isEqualTo("query_timeout=123,exec_mem_limit=2147483648,time_zone='Etc/UTC'")
    }
}
