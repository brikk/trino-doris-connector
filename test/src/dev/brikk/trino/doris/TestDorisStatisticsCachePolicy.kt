package dev.brikk.trino.doris

import io.trino.Session
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisStatisticsCachePolicy : AbstractTestQueryFramework() {
    private val schema = "r08_cache_${UUID.randomUUID().toString().replace("-", "")}"

    override fun createQueryRunner(): QueryRunner {
        DorisTestCluster.executeAsRoot("CREATE DATABASE $schema")
        for (name in listOf("enabled_first", "disabled_first", "catalog_default")) {
            DorisTestCluster.executeAsRoot("CREATE TABLE $schema.$name (id INT NOT NULL) DUPLICATE KEY(id) " +
                "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')",
                "INSERT INTO $schema.$name VALUES (1)", "ANALYZE TABLE $schema.$name WITH SYNC")
        }
        val runner = DorisQueryRunner.builder().addConnectorProperty("metadata.statistics.cache-ttl", "5m")
            .addConnectorProperty("metadata.cache-missing", "true").build()
        runner.createCatalog("stats_off", "doris", mapOf("connection-url" to DorisQueryRunner.JDBC_URL, "connection-user" to "root",
            "metadata.statistics.cache-ttl" to "5m", "metadata.cache-missing" to "true", "doris.statistics.enabled" to "false"))
        return runner
    }

    @AfterAll
    fun dropOwnFixture() { DorisTestCluster.executeAsRoot("DROP DATABASE IF EXISTS $schema") }

    @Test
    fun testEnabledCacheDoesNotLeakIntoDisabledSession() {
        val disabled = override("doris", false)
        assertCount(session, "doris", "enabled_first", 1.0)
        assertCount(disabled, "doris", "enabled_first", null)
        assertCount(session, "doris", "enabled_first", 1.0)
        val rows = computeActual(disabled, "SHOW STATS FOR doris.$schema.enabled_first").materializedRows
        assertThat(rows).allSatisfy { row -> for (field in 1..6) assertThat(row.getField(field)).isNull() }
    }

    @Test
    fun testDisabledFirstCannotPoisonEnabledMissingValueCache() {
        assertCount(override("doris", false), "doris", "disabled_first", null)
        assertCount(session, "doris", "disabled_first", 1.0)
        assertCount(override("doris", false), "doris", "disabled_first", null)
        assertThat(computeActual("SELECT count(*) FROM doris.$schema.disabled_first").onlyValue).isEqualTo(1L)
    }

    @Test
    fun testCatalogDisabledDefaultCanBeOverriddenWithoutCachePolicyLeakage() {
        assertCount(session, "stats_off", "catalog_default", null)
        assertCount(override("stats_off", true), "stats_off", "catalog_default", 1.0)
        assertCount(session, "stats_off", "catalog_default", null)
        assertCount(override("stats_off", true), "stats_off", "catalog_default", 1.0)
    }

    private fun override(catalog: String, enabled: Boolean): Session = Session.builder(session)
        .setCatalogSessionProperty(catalog, "statistics_enabled", enabled.toString()).build()

    private fun assertCount(session: Session, catalog: String, table: String, expected: Double?) {
        val rows = computeActual(session, "SHOW STATS FOR $catalog.$schema.$table").materializedRows
        assertThat(rows.single { it.getField(0) == null }.getField(4)).isEqualTo(expected)
    }
}
