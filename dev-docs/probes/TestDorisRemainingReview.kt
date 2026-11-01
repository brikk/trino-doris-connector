package dev.brikk.trino.doris

import com.mysql.cj.conf.ConnectionUrl
import com.mysql.cj.jdbc.NonRegisteringDriver
import io.airlift.units.Duration
import io.trino.Session
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Properties
import java.util.UUID

/** Diagnostic pins for OPEN findings, not assertions that these behaviors are safe. */
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
    fun testR08PositiveTtlCacheIgnoresSessionSwitchInBothDirections() {
        val disabled = Session.builder(session).setCatalogSessionProperty("doris", "statistics_enabled", "false").build()
        fun count(session: Session, table: String): Any? =
            computeActual(session, "SHOW STATS FOR doris.$schema.$table").materializedRows
                .single { it.getField(0) == null }.getField(4)
        val warm = count(session, "warm_enabled")
        val disabledAfterWarm = count(disabled, "warm_enabled")
        assertThat(warm).isEqualTo(1.0)
        assertThat(disabledAfterWarm).isEqualTo(warm)
        val coldDisabled = count(disabled, "warm_disabled")
        val enabledAfterDisabled = count(session, "warm_disabled")
        assertThat(coldDisabled).isNull()
        assertThat(enabledAfterDisabled).isNull()
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
    fun testR11TimeoutValuesRemainUnvalidatedAndNormalizeDifferently() {
        for (duration in listOf("0ms", "0.1ms", "30d")) {
            val config = DorisConfig().setConnectTimeout(Duration.valueOf(duration))
            val value = DorisClientModule.connectionProperties(config).getProperty("connectTimeout").toLong()
            assertThat(value == 0L || value > Int.MAX_VALUE).isTrue()
            println("R11|connect=$duration|driver-ms=$value")
        }
        val query = DorisConfig().setQueryTimeout(Duration.valueOf("100ms"))
        val initial = DorisClientModule.connectionProperties(query).getProperty("sessionVariables")
        assertThat(initial).isEqualTo("query_timeout=0")
        println("R11|query=100ms|open=$initial|scan-source-clamps-to-at-least-one-second")
    }

    @Test
    fun testR17UnsupportedProtocolAndEffectiveDatabaseStillPassValidation() {
        val mysqlx = DorisJdbcConfig().also { it.setConnectionUrl("mysqlx://fe:9030") }
        assertThat(mysqlx.isUrlValid()).isTrue()
        assertThat(NonRegisteringDriver().connect("mysqlx://fe:9030", Properties())).isNull()
        for (url in listOf(
            "jdbc:mysql://fe:9030/?DBNAME=hidden_db",
            "jdbc:mysql://address=(host=fe)(port=9030)(DBNAME=hidden_db)/",
        )) {
            val config = DorisJdbcConfig().also { it.setConnectionUrl(url) }
            assertThat(config.isUrlValid()).isTrue()
            assertThat(config.isUrlWithoutDatabase()).isTrue()
            val database = ConnectionUrl.getConnectionUrlInstance(url, Properties()).mainHost.database
            assertThat(database).isEqualTo("hidden_db")
            println("R17|accepted=$url|effective-db=$database")
        }
    }
}
