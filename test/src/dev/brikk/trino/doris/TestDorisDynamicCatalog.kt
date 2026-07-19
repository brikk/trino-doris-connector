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

import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * Dynamic catalog management (https://trino.io/docs/current/sql/create-catalog.html):
 * `CREATE CATALOG ... USING doris WITH (...)` must behave identically to a statically
 * configured catalog. Test recipe follows upstream 483 `TestDynamicCatalogs`:
 * `TestingTrinoServer`/`DistributedQueryRunner` run with dynamic catalog management and an
 * in-memory catalog store BY DEFAULT — no extra coordinator properties needed in tests. (A
 * real deployment needs `catalog.management=dynamic` in the coordinator+worker config.)
 *
 * NO static `doris` catalog is created here — everything goes through SQL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TestDorisDynamicCatalog : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner = DorisQueryRunner.dynamicCatalogBuilder().build()

    @Test
    @Order(1)
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
    @Order(2)
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
    @Order(3)
    fun testUnreachableHostSucceedsAtCreateAndFailsLoudAtFirstUse() {
        // Base JDBC opens connections LAZILY: a syntactically valid URL to an unreachable
        // host passes CREATE CATALOG (only config validation runs) and fails loudly on first
        // use — operator-facing semantics worth knowing.
        assertUpdate(
            """CREATE CATALOG doris_unreachable USING doris WITH ("connection-url" = 'jdbc:mysql://127.0.0.1:1', "connection-user" = 'root')""",
        )
        assertQueryFails("SHOW SCHEMAS FROM doris_unreachable", "(?s).*(refused|failure|link).*")
        assertUpdate("DROP CATALOG doris_unreachable")
    }

    companion object {
        private const val DYNAMIC_CATALOG = "doris_dyn"
    }
}
