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

import com.mysql.cj.conf.ConnectionUrlParser.parseConnectionString
import com.mysql.cj.exceptions.CJException
import com.mysql.cj.jdbc.Driver
import io.trino.plugin.jdbc.BaseJdbcConfig
import jakarta.validation.constraints.AssertTrue
import java.sql.SQLException

/**
 * Validates the Doris FE connection URL: a MySQL Connector/J `jdbc:mysql://` URL
 * WITHOUT a database path — Doris databases map to Trino schemas, so the connection
 * must not pin a default database (ledger §B; SR K3; PLAN G9/§4.4).
 */
class DorisJdbcConfig : BaseJdbcConfig() {
    @AssertTrue(message = "Invalid JDBC URL for the Doris connector: must be a jdbc:mysql:// URL accepted by MySQL Connector/J")
    fun isUrlValid(): Boolean {
        return try {
            Driver().acceptsURL(connectionUrl)
        } catch (ignored: SQLException) {
            false
        }
    }

    @AssertTrue(message = "Database (catalog) must not be specified in the JDBC URL for the Doris connector (Doris databases map to Trino schemas)")
    fun isUrlWithoutDatabase(): Boolean {
        return try {
            parseConnectionString(connectionUrl).path.isNullOrEmpty()
        } catch (ignored: CJException) {
            false
        }
    }
}
