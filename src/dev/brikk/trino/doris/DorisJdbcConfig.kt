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

import com.mysql.cj.conf.ConnectionUrl.Type
import com.mysql.cj.conf.ConnectionUrlParser
import com.mysql.cj.conf.ConnectionUrlParser.parseConnectionString
import com.mysql.cj.exceptions.CJException
import io.trino.plugin.jdbc.BaseJdbcConfig
import jakarta.validation.constraints.AssertTrue

/**
 * Validates the Doris FE connection URL: a supported MySQL Connector/J JDBC URL
 * WITHOUT a database path — Doris databases map to Trino schemas, so the connection
 * must not pin a default database (ledger §B; SR K3; PLAN G9/§4.4).
 */
class DorisJdbcConfig : BaseJdbcConfig() {
    @AssertTrue(message = "Invalid JDBC URL for the Doris connector: use a supported MySQL JDBC URL without propertiesTransform or useConfigs")
    fun isUrlValid(): Boolean = validate { _, _ -> true }

    @AssertTrue(message = "Database (catalog) must not be specified in the JDBC URL for the Doris connector (Doris databases map to Trino schemas)")
    fun isUrlWithoutDatabase(): Boolean = validate { parsed, properties ->
        parsed.path.isNullOrEmpty() && properties.none { entries ->
            entries.any { (key, value) -> key.equals("dbname", ignoreCase = true) && !value.isNullOrEmpty() }
        }
    }

    /** Lexical parser only: never instantiate ConnectionUrl, resolve DNS or load transforms/profiles. */
    private fun validate(check: (ConnectionUrlParser, List<Map<String, String>>) -> Boolean): Boolean {
        val url = connectionUrl ?: return false
        return try {
            val parsed = parseConnectionString(url)
            val hosts = parsed.hosts
            val type = Type.fromValue(parsed.scheme, hosts.size)
            val properties = listOf(parsed.properties) + hosts.map { it.hostProperties }
            type in JDBC_TYPES && properties.none { entries ->
                entries.any { (key, value) ->
                    (key.equals("propertiesTransform", ignoreCase = true) || key.equals("useConfigs", ignoreCase = true)) && !value.isNullOrEmpty()
                }
            } && check(parsed, properties)
        } catch (ignored: CJException) {
            false
        } catch (ignored: IllegalArgumentException) {
            false // malformed %-encoding/ports must be a validation failure, not an escaping exception
        }
    }

    companion object {
        private val JDBC_TYPES = setOf(Type.SINGLE_CONNECTION, Type.FAILOVER_CONNECTION, Type.LOADBALANCE_CONNECTION,
            Type.REPLICATION_CONNECTION, Type.FAILOVER_DNS_SRV_CONNECTION, Type.LOADBALANCE_DNS_SRV_CONNECTION,
            Type.REPLICATION_DNS_SRV_CONNECTION)
    }
}
