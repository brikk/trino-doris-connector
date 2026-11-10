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

import io.airlift.units.Duration
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisDynamicCatalog : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner = DorisQueryRunner.dynamicCatalogBuilder().build()

    @Test
    fun testCreateCatalogQueryAndDrop() {
        assertThat(computeActual("SHOW CATALOGS").onlyColumnAsSet).doesNotContain(DYNAMIC_CATALOG)

        // CREATE with the required properties plus a doris.* extra to prove extended config
        // round-trips through the WITH clause.
        assertUpdate(
            """
            CREATE CATALOG $DYNAMIC_CATALOG USING doris
            WITH (
                "connection-url" = '${DorisQueryRunner.JDBC_URL}',
                "connection-user" = '${DorisQueryRunner.USER}',
                "doris.query-timeout" = '300s'
            )
            """.trimIndent(),
        )
        assertThat(computeActual("SHOW CATALOGS").onlyColumnAsSet).contains(DYNAMIC_CATALOG)

        // The doris.* extra surfaced as the catalog session property default (config round-trip).
        val queryTimeout = computeActual("SHOW SESSION LIKE '$DYNAMIC_CATALOG.query\\_timeout' ESCAPE '\\'").materializedRows
        assertThat(queryTimeout).hasSize(1)
        assertThat(queryTimeout.single().getField(1).toString()).isEqualTo("300.00s")

        // Metadata + scalar reads + ARRAY decode behave identically to a static catalog.
        assertThat(computeActual("SHOW SCHEMAS FROM $DYNAMIC_CATALOG").onlyColumnAsSet).contains("p0_probe")
        assertThat(query("SELECT count(*) FROM $DYNAMIC_CATALOG.p0_probe.scalars_view")).matches("VALUES BIGINT '8'")
        assertThat(query("SELECT c_largeint FROM $DYNAMIC_CATALOG.p0_probe.scalars WHERE id = 4"))
            .matches("VALUES CAST(NULL AS decimal(38, 0))")
        assertThat(computeActual("SELECT a_int FROM $DYNAMIC_CATALOG.p0_probe.arrays WHERE id = 1").onlyValue)
            .isEqualTo(listOf(1, 2, 3))

        // Pushdown identical: numeric domain and the typed ARRAY rule both fully push.
        assertThat(query("SELECT n FROM $DYNAMIC_CATALOG.p0_probe.nums WHERE n > 1000995")).isFullyPushedDown()
        assertThat(query("SELECT id FROM $DYNAMIC_CATALOG.p0_probe.arrays WHERE contains(a_int, 1)")).isFullyPushedDown()

        // Read-only defense in depth is wired through the dynamic catalog too.
        assertQueryFails(
            "INSERT INTO $DYNAMIC_CATALOG.p0_probe.nums VALUES (999999999)",
            "Access Denied: Cannot insert into table p0_probe.nums",
        )
        assertThat(DorisTestCluster.queryScalar("SELECT COUNT(*) FROM p0_probe.nums")).isEqualTo("1001000")

        // DROP removes it; subsequent references fail loudly.
        assertUpdate("DROP CATALOG $DYNAMIC_CATALOG")
        assertThat(computeActual("SHOW CATALOGS").onlyColumnAsSet).doesNotContain(DYNAMIC_CATALOG)
        assertQueryFails("SELECT count(*) FROM $DYNAMIC_CATALOG.p0_probe.nums", ".*Catalog '$DYNAMIC_CATALOG' not found.*")
    }

    @Test
    fun testCreateCatalogWithInvalidUrlFailsLoudlyAtCreation() {
        // DorisJdbcConfig's @AssertTrue validation runs when CREATE CATALOG instantiates the
        // connector — a bad URL fails the CREATE statement itself, loudly, as configuration
        // errors (surfaced as a QueryFailedException, not a TrinoException wrapper — hence
        // assertThatThrownBy rather than assertQueryFails).
        assertCreateCatalogFails(
            """CREATE CATALOG doris_bad USING doris WITH ("connection-url" = 'jdbc:postgresql://example:5432', "connection-user" = 'root')""",
            "Invalid JDBC URL for the Doris connector",
        )
        assertCreateCatalogFails(
            """CREATE CATALOG doris_bad USING doris WITH ("connection-url" = 'jdbc:mysql://example:9030/somedb', "connection-user" = 'root')""",
            "Database (catalog) must not be specified",
        )
        for (url in listOf("mysqlx://example:9030", "jdbc:mysql+srv://example.invalid/", "jdbc:mysql://example:9030/?DBNAME=hidden_db",
            "jdbc:mysql://address=(host=example)(port=9030)(DBNAME=hidden_db)/",
            "jdbc:mysql://example:9030/?propertiesTransform=java.lang.String", "jdbc:mysql://example:9030/?useConfigs=maxPerformance")) {
            assertCreateCatalogFails(
                "CREATE CATALOG doris_bad USING doris WITH (\"connection-url\"='$url',\"connection-user\"='root')",
                if (url.contains("DBNAME")) "Database (catalog) must not be specified" else "Invalid JDBC URL for the Doris connector",
            )
        }
        // Missing required property also fails at CREATE.
        assertCreateCatalogFails(
            """CREATE CATALOG doris_bad USING doris WITH ("connection-user" = 'root')""",
            "connection-url",
        )
        assertThat(computeActual("SHOW CATALOGS").onlyColumnAsSet).doesNotContain("doris_bad")
    }

    private fun assertCreateCatalogFails(sql: String, expectedMessageFragment: String) {
        org.assertj.core.api.Assertions.assertThatThrownBy { computeActual(sql) }
            .describedAs(sql)
            .hasMessageContaining(expectedMessageFragment)
    }

    @Test
    fun testUnreachableHostSucceedsAtCreateAndFailsLoudAtFirstUse() {
        // Base JDBC opens connections LAZILY: a syntactically valid URL to an unreachable
        // host passes CREATE CATALOG (only config validation runs) and fails loudly on first
        // use — operator-facing semantics worth knowing.
        assertUpdate(
            """CREATE CATALOG doris_unreachable USING doris WITH ("connection-url" = 'jdbc:mysql://127.0.0.1:1', "connection-user" = 'root')""",
        )
        assertQueryFails("SHOW SCHEMAS FROM doris_unreachable", "(?s).*(refused|failure|link).*")
        assertUpdate("DROP CATALOG doris_unreachable")
        // The base JDBC URL constraint excludes direct mysql+srv schemes; the
        // driver's option form remains valid and does not resolve DNS at CREATE.
        assertUpdate("CREATE CATALOG doris_srv USING doris WITH (\"connection-url\"='jdbc:mysql://does-not-exist.invalid/?dnsSrv=true'," +
            "\"connection-user\"='root')")
        assertUpdate("DROP CATALOG doris_srv")
    }

    @Test
    fun testTimeoutRangesFailAtConfigurationAndNativeSessionRoundingAgrees() {
        for ((property, value) in listOf(
            "doris.connect-timeout" to "0ms", "doris.connect-timeout" to "0.1ms", "doris.connect-timeout" to "30d",
            "doris.query-timeout" to "100ms", "doris.query-timeout" to "2147483648s",
        )) {
            val sql = "CREATE CATALOG doris_bad_timeout USING doris WITH (\"connection-url\"='${DorisQueryRunner.JDBC_URL}', " +
                "\"connection-user\"='root',\"$property\"='$value')"
            // Airlift reports setter invocation failures at the top level; the
            // supported-range diagnostic is retained in the configuration cause.
            org.assertj.core.api.Assertions.assertThatThrownBy { computeActual(sql) }
                .describedAs(sql).rootCause().hasMessageContaining("must be between")
        }
        assertUpdate("CREATE CATALOG doris_timeout USING doris WITH (\"connection-url\"='${DorisQueryRunner.JDBC_URL}', " +
            "\"connection-user\"='root',\"doris.query-timeout\"='1.1s')")
        try {
            assertThat(query("SELECT count(*) FROM doris_timeout.p0_probe.scalars_view")).matches("VALUES BIGINT '8'")
            assertCreateCatalogFails("SET SESSION doris_timeout.query_timeout = '100ms'", "must be between")
        } finally {
            assertUpdate("DROP CATALOG doris_timeout")
        }
        for ((text, expected) in listOf("1.1s" to 2, "2147483647s" to Int.MAX_VALUE)) {
            val props = DorisClientModule.connectionProperties(DorisConfig().setQueryTimeout(Duration.valueOf(text))).apply {
                setProperty("user", "root")
            }
            DorisTestSafety.openSingle { requireNotNull(DorisReadDriver().connect(DorisQueryRunner.JDBC_URL, props)) }.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT @@query_timeout").use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getInt(1)).isEqualTo(expected)
                    }
                }
            }
        }
    }

    companion object {
        private const val DYNAMIC_CATALOG = "doris_dyn"
    }
}
