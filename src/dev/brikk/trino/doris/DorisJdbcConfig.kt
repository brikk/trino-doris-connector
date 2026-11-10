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

class DorisJdbcConfig : BaseJdbcConfig() {
    @AssertTrue(
        message = "Invalid JDBC URL for the Doris connector: " +
            "use a supported MySQL JDBC URL without propertiesTransform or useConfigs",
    )
    fun isUrlValid(): Boolean {
        return isSupportedUrl { _, _ -> true }
    }

    @AssertTrue(
        message = "Database (catalog) must not be specified in the JDBC URL for the Doris connector " +
            "(Doris databases map to Trino schemas)",
    )
    fun isUrlWithoutDatabase(): Boolean {
        return isSupportedUrl { parsed, properties ->
            parsed.path.isNullOrEmpty() && properties.none { it.hasNonEmptyValue("dbname") }
        }
    }

    /** Parses lexically: building a ConnectionUrl would resolve DNS and load transforms or config profiles. */
    private fun isSupportedUrl(check: (ConnectionUrlParser, List<Map<String, String>>) -> Boolean): Boolean {
        val url = connectionUrl ?: return false
        try {
            val parsed = parseConnectionString(url)
            val hosts = parsed.hosts
            if (Type.fromValue(parsed.scheme, hosts.size) !in JDBC_TYPES) {
                return false
            }
            val properties = listOf(parsed.properties) + hosts.map { it.hostProperties }
            if (properties.any { it.hasNonEmptyValue("propertiesTransform") || it.hasNonEmptyValue("useConfigs") }) {
                return false
            }
            return check(parsed, properties)
        } catch (ignored: CJException) {
            return false
        } catch (ignored: IllegalArgumentException) {
            // malformed %-encoding/ports must be a validation failure, not an escaping exception
            return false
        }
    }

    companion object {
        // BaseJdbcConfig's own URL pattern already rejects the mysql+srv schemes.
        private val JDBC_TYPES = setOf(
            Type.SINGLE_CONNECTION,
            Type.FAILOVER_CONNECTION,
            Type.LOADBALANCE_CONNECTION,
            Type.REPLICATION_CONNECTION,
        )

        private fun Map<String, String>.hasNonEmptyValue(key: String): Boolean {
            return entries.any { (name, value) -> name.equals(key, ignoreCase = true) && !value.isNullOrEmpty() }
        }
    }
}
