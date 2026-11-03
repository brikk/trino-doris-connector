package dev.brikk.trino.doris

import io.airlift.units.Duration
import io.trino.Session
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/** Optional rechecks of the resolved statistics, targeting, timeout and URL contracts. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisRemainingReview : AbstractTestQueryFramework() {
    private val schema = "hardening_review_${UUID.randomUUID().toString().replace("-", "")}"

    override fun createQueryRunner(): QueryRunner {
        DorisTestCluster.requireGoldenVersion()
        DorisTestCluster.executeAsRoot("CREATE DATABASE $schema")
        for (table in listOf("warm_enabled", "warm_disabled")) {
            DorisTestCluster.executeAsRoot(
                "CREATE TABLE $schema.$table (id INT NOT NULL) DUPLICATE KEY(id) " +
                    "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')",
                "INSERT INTO $schema.$table VALUES (1)",
                "ANALYZE TABLE $schema.$table WITH SYNC",
            )
        }
        return DorisQueryRunner.builder()
            .addConnectorProperty("metadata.statistics.cache-ttl", "5m")
            .addConnectorProperty("metadata.cache-missing", "true")
            .build()
    }

    @AfterAll
    fun dropOwnFixture() {
        DorisTestCluster.executeAsRoot("DROP DATABASE IF EXISTS $schema")
    }

    @Test
    fun testR08PositiveTtlCacheHonorsSessionSwitchInBothDirections() {
        val disabled = Session.builder(session).setCatalogSessionProperty("doris", "statistics_enabled", "false").build()
        fun count(session: Session, table: String): Any? =
            computeActual(session, "SHOW STATS FOR doris.$schema.$table").materializedRows
                .single { it.getField(0) == null }.getField(4)
        val warm = count(session, "warm_enabled")
        val disabledAfterWarm = count(disabled, "warm_enabled")
        assertThat(warm).isEqualTo(1.0)
        assertThat(disabledAfterWarm).isNull()
        val coldDisabled = count(disabled, "warm_disabled")
        val enabledAfterDisabled = count(session, "warm_disabled")
        assertThat(coldDisabled).isNull()
        assertThat(enabledAfterDisabled).isEqualTo(1.0)
        println("R08|enabled-first=$warm|disabled-after=$disabledAfterWarm|disabled-first=$coldDisabled|enabled-after=$enabledAfterDisabled")
    }

    @Test
    fun testR10MarkerInsideUnrelatedSqlLiteralIsNotACandidate() {
        val marker = "trino_statement_id=${UUID.randomUUID()}"
        val candidate = DorisClusterScopedCancel.isRunningCancelCandidate("Query", "SELECT '/*$marker*/'", marker)
        assertThat(candidate).isFalse()
        println("R10|marker-inside-literal-is-candidate=$candidate|fixed-leading-comment-boundary|no-live-kill-issued")
    }

    @Test
    fun testR11TimeoutBoundsAndSharedNormalization() {
        for (duration in listOf("0ms", "0.1ms", "30d")) {
            assertThatThrownBy { DorisConfig().setConnectTimeout(Duration.valueOf(duration)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { DorisConfig().setQueryTimeout(Duration.valueOf("100ms")) }
            .isInstanceOf(IllegalArgumentException::class.java)
        val query = DorisConfig().setQueryTimeout(Duration.valueOf("1.1s"))
        val initial = DorisClientModule.connectionProperties(query).getProperty("sessionVariables")
        assertThat(initial).isEqualTo("query_timeout=2")
        assertThat(DorisTimeouts.querySeconds(query.getQueryTimeout()!!)).isEqualTo(2)
        println("R11|invalid-ranges-rejected|query=1.1s|open=$initial|scan=2")
    }

    @Test
    fun testR17UnsupportedProtocolAndEffectiveDatabaseAreRejected() {
        val mysqlx = DorisJdbcConfig().also { it.setConnectionUrl("mysqlx://fe:9030") }
        assertThat(mysqlx.isUrlValid()).isFalse()
        for (url in listOf(
            "jdbc:mysql://fe:9030/?DBNAME=hidden_db",
            "jdbc:mysql://address=(host=fe)(port=9030)(DBNAME=hidden_db)/",
        )) {
            val config = DorisJdbcConfig().also { it.setConnectionUrl(url) }
            assertThat(config.isUrlValid()).isTrue()
            assertThat(config.isUrlWithoutDatabase()).isFalse()
            println("R17|rejected-database-setting=$url|no-connection-or-transform")
        }
    }
}
