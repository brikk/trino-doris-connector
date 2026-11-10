package dev.brikk.trino.doris

import io.trino.Session
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

// Base NOT NULL payloads become nullable on an outer join's null-supplying side.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisOuterJoinSafety : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        DorisTestCluster.requireGoldenVersion()
        DorisTestCluster.executeAsRoot(
            "CREATE DATABASE IF NOT EXISTS outer_join_safety",
            "DROP TABLE IF EXISTS outer_join_safety.l",
            "DROP TABLE IF EXISTS outer_join_safety.r",
            "CREATE TABLE outer_join_safety.l (id INT NOT NULL,v INT NOT NULL) DUPLICATE KEY(id) " +
                "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')",
            "CREATE TABLE outer_join_safety.r (id INT NOT NULL,v INT NOT NULL) DUPLICATE KEY(id) " +
                "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')",
            "INSERT INTO outer_join_safety.l VALUES (1,60),(2,60),(3,60)",
            "INSERT INTO outer_join_safety.r VALUES (0,60),(2,60),(4,60)",
        )
        return DorisQueryRunner.builder().build()
    }

    private val eager: Session get() = Session.builder(session)
        .setCatalogSessionProperty("doris", "join_pushdown_enabled", "true")
        .setCatalogSessionProperty("doris", "join_pushdown_strategy", "EAGER").build()

    @Test
    fun testBothOuterSidesAndBothAggregatesRetainNullPayloads() {
        val local = fullyLocalSession(eager)
        for ((from, arguments) in listOf(
            "doris.outer_join_safety.l l LEFT JOIN doris.outer_join_safety.r r ON l.id=r.id" to "r.v,l.id",
            "doris.outer_join_safety.l l RIGHT JOIN doris.outer_join_safety.r r ON l.id=r.id" to "l.v,r.id",
        )) {
            for (function in listOf("min_by", "max_by")) {
                val sql = "SELECT $function($arguments) FROM $from"
                assertThat(computeActual(eager, sql).onlyValue).describedAs(sql).isNull()
                assertThat(computeActual(eager, sql).materializedRows).isEqualTo(computeActual(local, sql).materializedRows)
                assertThat(query(eager, sql)).joinIsNotFullyPushedDown()
            }
        }
    }

    @Test
    fun testBaseAndMatchedInnerAggregatesRemainEligible() {
        for (function in listOf("min_by", "max_by")) {
            for (sql in listOf(
                "SELECT $function(v,id) FROM doris.outer_join_safety.r",
                "SELECT $function(r.v,l.id) FROM doris.outer_join_safety.l l JOIN doris.outer_join_safety.r r ON l.id=r.id",
            )) {
                assertThat(query(eager, sql)).isFullyPushedDown()
                assertThat(computeActual(eager, sql).onlyValue).isEqualTo(60)
            }
        }
    }
}
