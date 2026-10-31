package dev.brikk.trino.doris

import io.trino.Session
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Opt-in diagnostic: asserts correct results, so an unfixed R-04 deliberately fails. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisR04Review : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        DorisTestCluster.requireGoldenVersion()
        DorisTestCluster.executeAsRoot(
            "CREATE DATABASE IF NOT EXISTS r04_484_review",
            "DROP TABLE IF EXISTS r04_484_review.l",
            "DROP TABLE IF EXISTS r04_484_review.r",
            "CREATE TABLE r04_484_review.l (id INT NOT NULL) DUPLICATE KEY(id) " +
                "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')",
            "CREATE TABLE r04_484_review.r (id INT NOT NULL, v INT NOT NULL) DUPLICATE KEY(id) " +
                "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')",
            "INSERT INTO r04_484_review.l VALUES (1),(2),(3)",
            "INSERT INTO r04_484_review.r VALUES (2,60)",
        )
        return DorisQueryRunner.builder().build()
    }

    private val eager: Session get() = Session.builder(session)
        .setCatalogSessionProperty("doris", "join_pushdown_enabled", "true")
        .setCatalogSessionProperty("doris", "join_pushdown_strategy", "EAGER").build()

    private val local: Session get() = Session.builder(session)
        .setSystemProperty("allow_pushdown_into_connectors", "false").build()

    @Test
    fun leftJoinNullSupplyingPayloadMustAgree() = checkOuterJoin(
        "doris.r04_484_review.l l LEFT JOIN doris.r04_484_review.r r ON l.id = r.id",
    )

    @Test
    fun rightJoinNullSupplyingPayloadMustAgree() = checkOuterJoin(
        "doris.r04_484_review.r r RIGHT JOIN doris.r04_484_review.l l ON l.id = r.id",
    )

    private fun checkOuterJoin(from: String) {
        val mismatches = mutableListOf<String>()
        for (function in listOf("min_by", "max_by")) {
            val sql = "SELECT $function(r.v,l.id) FROM $from"
            val expected = computeActual(local, sql).onlyValue
            val actual = computeActual(eager, sql).onlyValue
            val default = computeActual(sql).onlyValue
            val plan = computeActual(eager, "EXPLAIN $sql").onlyValue
            println("R04|sql=$sql|local=$expected|eager=$actual|default=$default\n$plan")
            if (actual != expected) mismatches.add("$function: eager=$actual, local=$expected")
            assertThat(default).describedAs("join pushdown OFF control").isEqualTo(expected)
        }
        assertThat(mismatches).describedAs("R-04 outer-join aggregate mismatches").isEmpty()
    }

    @Test
    fun nonNullBaseAndMatchedInnerJoinControls() {
        for (function in listOf("min_by", "max_by")) {
            for (sql in listOf(
                "SELECT $function(v,id) FROM doris.r04_484_review.r",
                "SELECT $function(r.v,l.id) FROM doris.r04_484_review.l l JOIN doris.r04_484_review.r r ON l.id=r.id",
            )) {
                val actual = computeActual(eager, sql).onlyValue
                println("R04-CONTROL|sql=$sql|value=$actual")
                assertThat(actual).isEqualTo(60)
                assertThat(query(eager, sql)).isFullyPushedDown()
            }
        }
    }
}
