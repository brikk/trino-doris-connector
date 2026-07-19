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

import io.trino.Session
import io.trino.spi.type.TimeZoneKey
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.TimeZone

/**
 * Timezone audit (TODO-timezones items 1-3, test-only): Doris DATETIME/DATE are ZONELESS and
 * map to zoneless Trino types, so every zone knob must be a NO-OP for pushed queries —
 * {Trino session zone} x {doris.time-zone} x {JVM default zone} must never change results
 * or the pushed literal bytes. DST-adversarial wall-clock values (nonexistent and ambiguous
 * local times in America/New_York) and the 0000-01-01/9999-12-31 date edges are exactly
 * where driver-side zone conversion bugs bite first — pinned here.
 *
 * `doris.time-zone` variation runs through EXTRA CATALOGS on the same cluster (tz_tokyo /
 * tz_ny differ from `doris` only in that property) — the canary asserts one representative
 * query per pushed family is result-identical across all three.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisTimezoneAudit : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        val runner = DorisQueryRunner.builder().build()
        for ((catalog, zone) in listOf("tz_tokyo" to "Asia/Tokyo", "tz_ny" to "America/New_York")) {
            runner.createCatalog(
                catalog,
                "doris",
                mapOf(
                    "connection-url" to DorisQueryRunner.JDBC_URL,
                    "connection-user" to DorisQueryRunner.USER,
                    "doris.time-zone" to zone,
                ),
            )
        }
        return runner
    }

    private fun sessionInZone(zoneId: String): Session = Session.builder(session)
        .setTimeZoneKey(TimeZoneKey.getTimeZoneKey(zoneId))
        .build()

    private fun rows(session: Session, sql: String): List<String> =
        computeActual(session, sql).materializedRows.map { it.toString() }.sorted()

    private fun remoteSql(session: Session, sql: String): String {
        val execution = getDistributedQueryRunner().executeWithPlan(session, sql)
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        return DorisTestCluster.awaitAuditLogStatements(marker, 60_000).single { it.contains("p5_tz") }
    }

    // --- item 1: parameter-binding zone matrix ---

    @Test
    fun testPushedTemporalLiteralsAreZoneIndependent() {
        // DST-adversarial predicates: 02:30 on 2026-03-08 does not EXIST in America/New_York;
        // 01:30 on 2026-11-01 is AMBIGUOUS there. Zoneless passthrough must not care.
        val predicates = mapOf(
            "dt = TIMESTAMP '2026-03-08 02:30:00'" to "'2026-03-08 02:30:00'",
            "dt = TIMESTAMP '2026-11-01 01:30:00'" to "'2026-11-01 01:30:00'",
            "dt6 = TIMESTAMP '2026-03-08 02:30:00.123456'" to "'2026-03-08 02:30:00.123456'",
            "d = DATE '0000-01-01'" to "'0000-01-01'",
            "d = DATE '9999-12-31'" to "'9999-12-31'",
            "d = DATE '2026-03-08'" to "'2026-03-08'",
        )
        val zones = listOf("UTC", "America/New_York", "Asia/Tokyo")
        for ((predicate, expectedLiteral) in predicates) {
            val sql = "SELECT id FROM doris.p5_tz.t WHERE $predicate"
            val reference = rows(sessionInZone(zones.first()), sql)
            assertThat(reference).describedAs(predicate).isNotEmpty() // every predicate matches a row
            for (zone in zones) {
                val zoned = sessionInZone(zone)
                assertThat(query(zoned, sql)).describedAs("$predicate @ $zone").isFullyPushedDown()
                assertThat(rows(zoned, sql)).describedAs("$predicate @ $zone").isEqualTo(reference)
                // the pushed literal is the exact zoneless wall-clock text, independent of zone
                assertThat(remoteSql(zoned, sql)).describedAs("$predicate @ $zone").contains(expectedLiteral)
            }
            // ... and independent of doris.time-zone (different catalog, same everything else)
            for (catalog in listOf("tz_tokyo", "tz_ny")) {
                assertThat(rows(session, "SELECT id FROM $catalog.p5_tz.t WHERE $predicate"))
                    .describedAs("$predicate @ catalog $catalog")
                    .isEqualTo(reference)
            }
        }
    }

    @Test
    fun testJvmDefaultZoneDoesNotLeakIntoBinding() {
        // A misconfigured driver-JVM default zone must not shift pushed literals (this is
        // where java.sql.Date/Timestamp-based write functions would break; the connector
        // uses LocalDate/LocalDateTime binding — proven here by mutation).
        val sql = "SELECT id FROM doris.p5_tz.t WHERE dt = TIMESTAMP '2026-03-08 02:30:00' OR d = DATE '0000-01-01'"
        val reference = rows(session, sql)
        val original = TimeZone.getDefault()
        try {
            // UTC+14 — the most extreme offset; any epoch-day/zone-shift bug moves a date
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            assertThat(rows(session, sql)).isEqualTo(reference)
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertThat(rows(session, sql)).isEqualTo(reference)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // --- item 2: session-zone variation over the key datetime paths ---

    @Test
    fun testDatetimePathsAreSessionZoneInvariant() {
        val paths = listOf(
            // domain pushdown over datetime
            "SELECT id FROM doris.p5_tz.t WHERE dt BETWEEN TIMESTAMP '2026-03-08 00:00:00' AND TIMESTAMP '2026-11-01 12:00:00'",
            // ARRAY<DATETIME(6)> wire read (decoder path) — element-wise text compare
            "SELECT id, CAST(elem AS varchar) FROM doris.p5_tz.t CROSS JOIN UNNEST(adt) AS u(elem)",
            // min/max(datetime) aggregate pushdown
            "SELECT min(dt6), max(dt6) FROM doris.p5_tz.t",
            // datetime TopN pushdown
            "SELECT dt FROM doris.p5_tz.t ORDER BY dt ASC NULLS LAST LIMIT 3",
            // plain datetime projection (scalar read path incl. the 0000/9999 edges)
            "SELECT id, CAST(d AS varchar), CAST(dt6 AS varchar) FROM doris.p5_tz.t",
        )
        for (sql in paths) {
            val reference = computeActual(sessionInZone("UTC"), sql).materializedRows.map { it.toString() }.sorted()
            for (zone in listOf("America/New_York", "Asia/Tokyo")) {
                assertThat(rows(sessionInZone(zone), sql)).describedAs("$sql @ $zone").isEqualTo(reference)
            }
            // non-UTC doris.time-zone leg (Tokyo catalog, same physical data)
            val viaTokyo = sql.replace("doris.p5_tz", "tz_tokyo.p5_tz")
            assertThat(rows(session, viaTokyo)).describedAs(viaTokyo).isEqualTo(reference)
        }
    }

    // --- item 3: doris.time-zone canary, one representative per pushed family ---

    @Test
    fun testDorisTimeZoneCanaryAcrossEveryPushedFamily() {
        val joinPushdown = Session.builder(session)
            .setCatalogSessionProperty("tz_ny", "join_pushdown_enabled", "true")
            .setCatalogSessionProperty("tz_ny", "join_pushdown_strategy", "EAGER")
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "join_pushdown_enabled", "true")
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "join_pushdown_strategy", "EAGER")
            .build()
        val families = mapOf(
            "domains" to "SELECT id FROM CATALOG.p5_tz.t WHERE n = 2 AND dt >= TIMESTAMP '2026-01-01 00:00:00'",
            "contains" to "SELECT id FROM CATALOG.p5_tz.t WHERE contains(ai, 2)",
            "array_position" to "SELECT id FROM CATALOG.p5_tz.t WHERE array_position(ai, 2) = 2",
            "cardinality" to "SELECT id FROM CATALOG.p5_tz.t WHERE cardinality(ai) = 3",
            "json" to "SELECT id FROM CATALOG.p5_tz.t WHERE json_extract_scalar(doc, '$.a') = 'x'",
            "like-prefix (GUARDED)" to "SELECT id FROM CATALOG.p5_tz.t WHERE v LIKE 'foo%'",
            "aggregates" to "SELECT count(*), min(dt6), max(d) FROM CATALOG.p5_tz.t",
            "join (EAGER)" to "SELECT a.v, b.v FROM CATALOG.p5_tz.t a JOIN CATALOG.p5_tz.t b ON a.n = b.n",
        )
        for ((family, template) in families) {
            val reference = rows(joinPushdown, template.replace("CATALOG", "doris"))
            for (catalog in listOf("tz_tokyo", "tz_ny")) {
                assertThat(rows(joinPushdown, template.replace("CATALOG", catalog)))
                    .describedAs("family=$family catalog=$catalog (doris.time-zone must be a no-op)")
                    .isEqualTo(reference)
            }
        }
    }

    companion object {
        private fun provisionFixture() {
            DorisTestCluster.executeAsRoot(
                "DROP DATABASE IF EXISTS p5_tz",
                "CREATE DATABASE p5_tz",
                """
                CREATE TABLE p5_tz.t (
                    id INT NOT NULL, n INT, d DATE, dt DATETIME, dt6 DATETIME(6),
                    adt ARRAY<DATETIME(6)>, ai ARRAY<INT>, doc JSON, v VARCHAR(50)
                ) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                INSERT INTO p5_tz.t VALUES
                (1, 1, '0000-01-01', '2026-03-08 02:30:00', '2026-03-08 02:30:00.123456',
                 ['2026-03-08 02:30:00.123456', '2026-11-01 01:30:00.000001'], [1, 2, 3], '{"a": "x"}', 'foo'),
                (2, 2, '9999-12-31', '2026-11-01 01:30:00', '2026-11-01 01:30:00.000001',
                 ['0000-01-01 00:00:00.000000', '9999-12-31 23:59:59.999999'], [2, 2, 2], '{"a": "y"}', 'foobar'),
                (3, 2, '2026-03-08', '2026-06-15 12:00:00', '2026-06-15 12:00:00.500000',
                 [], [3], '{"b": 1}', 'other'),
                (4, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)
                """.trimIndent(),
            )
        }
    }
}
