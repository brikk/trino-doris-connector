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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Live pins of the Doris 4.1.3 aggregate semantics that GATE every P4 family decision
 * (PLAN §6.5 "Verify NULL, overflow, DECIMAL scale, and approximate-function behavior before
 * each rule"; full narrative in `dev-docs/NOTES-p4-aggregates.md`). Direct FE connection, no
 * Trino: these facts are properties of Doris itself. If Doris ever fixes/changes any of them
 * (e.g. starts ERRORING on sum overflow), a pin goes red and the family verdict must be
 * re-derived — never silently.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisP4AggregateProbes {
    @BeforeAll
    fun provision() {
        DorisTestCluster.executeAsRoot(
            "CREATE DATABASE IF NOT EXISTS p4_agg",
            "DROP TABLE IF EXISTS p4_agg.probe_pin",
            "DROP TABLE IF EXISTS p4_agg.probe_pin_avg",
            "DROP TABLE IF EXISTS p4_agg.probe_pin_avgb",
            "DROP VIEW IF EXISTS p4_agg.probe_pin_types",
            """
            CREATE TABLE p4_agg.probe_pin (
                id INT NOT NULL,
                c_big BIGINT, c_li LARGEINT, c_dec92 DECIMALV3(9,2), c_dec380 DECIMALV3(38,0), c_dec382 DECIMALV3(38,2),
                c_double DOUBLE, c_bool BOOLEAN, c_date DATE, c_dt6 DATETIME(6), c_int INT
            ) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num' = '1')
            """.trimIndent(),
            // id 1..2: bigint MAX + 1 (wrap probe); decimal(38,*) max pairs (wrap probes);
            // largeint 4 x 2^126 across ids 1..4 (2^128 wrap-to-zero probe);
            // double NaN/Infinity/-0.0/0.0 (ordering probes); NULL row for NULL semantics.
            """
            INSERT INTO p4_agg.probe_pin VALUES
            (1, 9223372036854775807, CAST('85070591730234615865843651857942052864' AS LARGEINT), 1.10,
                99999999999999999999999999999999999999, 999999999999999999999999999999999999.99,
                CAST('nan' AS DOUBLE), true, '2021-06-15', '2021-06-15 12:34:56.789012', 1),
            (2, 1, CAST('85070591730234615865843651857942052864' AS LARGEINT), 2.20,
                1, 999999999999999999999999999999999999.99,
                CAST('inf' AS DOUBLE), false, '0000-01-01', '0000-01-01 00:00:00.000000', 1),
            (3, NULL, CAST('85070591730234615865843651857942052864' AS LARGEINT), NULL, NULL, NULL,
                CAST('-0.0' AS DOUBLE), NULL, NULL, NULL, 2),
            (4, NULL, CAST('85070591730234615865843651857942052864' AS LARGEINT), NULL, NULL, NULL,
                0.0, NULL, NULL, NULL, NULL)
            """.trimIndent(),
            "CREATE TABLE p4_agg.probe_pin_avg (id INT, v DECIMALV3(9,4)) DUPLICATE KEY(id) " +
                "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num' = '1')",
            "INSERT INTO p4_agg.probe_pin_avg VALUES (1, 0.0001), (2, 0.0000)",
            "CREATE TABLE p4_agg.probe_pin_avgb (id INT, v BIGINT) DUPLICATE KEY(id) " +
                "DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num' = '1')",
            "INSERT INTO p4_agg.probe_pin_avgb VALUES (1, 9007199254740992), (2, 1), (3, -9007199254740992), (4, 1)",
            """
            CREATE VIEW p4_agg.probe_pin_types AS SELECT
                sum(c_big) s_big, sum(c_li) s_li, sum(c_dec92) s_dec92, count(*) c_all, count(DISTINCT c_int) c_d,
                min(c_bool) mn_bool, min(c_date) mn_date, min(c_dt6) mn_dt6, min(c_li) mn_li,
                avg(c_dec92) a_dec92, avg(c_big) a_big
            FROM p4_agg.probe_pin
            """.trimIndent(),
        )
    }

    /** sum(BIGINT) wraps SILENTLY at 2^64 where Trino throws -> family excluded. */
    @Test
    fun pinSumBigintWrapsSilently() {
        assertThat(DorisTestCluster.queryScalar("SELECT sum(c_big) FROM p4_agg.probe_pin"))
            .isEqualTo("-9223372036854775808")
    }

    /**
     * sum(DECIMALV3(38,s)) overflow is silent Int128 WRAPAROUND (2 x max DECIMALV3(38,2) =
     * 2*10^38-2 wraps past 2^127) — not an error, despite `check_overflow_for_decimal=true`.
     * The mysql CLI shows `-1402823669209384634633746074317682114.5` followed by a NUL byte;
     * over JDBC the wire bytes are so malformed that even `ResultSet.getString` THROWS
     * (Connector/J decimal parse: 'missing "e" notation exponential mark'). Trino throws
     * "Decimal overflow" -> sum stays local for p > 18 (see DorisImplementSum for the
     * unreachability bound that admits p <= 18).
     */
    @Test
    fun pinSumDecimalOverflowWrapsSilently() {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            DorisTestCluster.queryScalar("SELECT sum(c_dec382) FROM p4_agg.probe_pin")
        }.isInstanceOf(NumberFormatException::class.java)
            .hasMessageContaining("exponential mark")
    }

    /** sum(LARGEINT) wraps silently at 2^128: 4 x 2^126 -> 0. Family excluded. */
    @Test
    fun pinSumLargeintWrapsToZero() {
        assertThat(DorisTestCluster.queryScalar("SELECT sum(c_li) FROM p4_agg.probe_pin"))
            .isEqualTo("0")
    }

    /**
     * Result-type contract every enabled rule's synthesized type handle relies on:
     * sum(DECIMALV3(p,s)) -> decimalv3(38,s); count/count(DISTINCT) -> bigint; min/max return
     * the argument type unchanged (BOOLEAN as tinyint(1), LARGEINT as largeint, DATE/DATETIME
     * as themselves). Also pins the avg intermediate types the avg-skip verdict cites.
     */
    @Test
    fun pinAggregateResultColumnTypes() {
        val types = DorisTestCluster.openRootConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COLUMN_NAME, COLUMN_TYPE FROM information_schema.columns " +
                        "WHERE TABLE_SCHEMA = 'p4_agg' AND TABLE_NAME = 'probe_pin_types'",
                ).use { resultSet ->
                    buildMap {
                        while (resultSet.next()) {
                            put(resultSet.getString(1), resultSet.getString(2))
                        }
                    }
                }
            }
        }
        assertThat(types).containsEntry("s_big", "bigint(20)")
            .containsEntry("s_li", "largeint")
            .containsEntry("s_dec92", "decimalv3(38, 2)")
            .containsEntry("c_all", "bigint(20)")
            .containsEntry("c_d", "bigint(20)")
            .containsEntry("mn_bool", "tinyint(1)")
            .containsEntry("mn_date", "date")
            .containsEntry("mn_dt6", "datetime(6)")
            .containsEntry("mn_li", "largeint")
            .containsEntry("a_dec92", "decimalv3(38, 4)")
            .containsEntry("a_big", "double")
    }

    /**
     * avg(DECIMALV3(p,s)) computes at scale max(s, 4) with TRUNCATION, not Trino's HALF_UP:
     * avg{0.0001, 0.0000} (exact 0.00005) -> 0.0000 where Trino returns 0.0001. Divergent for
     * s >= 4 even under ImplementAvgDecimal's CAST wrapper -> avg never pushed.
     */
    @Test
    fun pinAvgDecimalTruncatesWhereTrinoRoundsHalfUp() {
        assertThat(DorisTestCluster.queryScalar("SELECT avg(v) FROM p4_agg.probe_pin_avg")).isEqualTo("0.0000")
        assertThat(DorisTestCluster.queryScalar("SELECT CAST(avg(v) AS DECIMALV3(9,4)) FROM p4_agg.probe_pin_avg"))
            .isEqualTo("0.0000")
        // the trunc-then-cast identity that WOULD hold for s <= 3 relies on CAST rounding
        // half-away-from-zero — pinned here for the record
        assertThat(DorisTestCluster.queryScalar("SELECT CAST(CAST('0.005' AS DECIMALV3(9,4)) AS DECIMALV3(9,2))")).isEqualTo("0.01")
        assertThat(DorisTestCluster.queryScalar("SELECT CAST(CAST('-0.005' AS DECIMALV3(9,4)) AS DECIMALV3(9,2))")).isEqualTo("-0.01")
    }

    /**
     * avg(DECIMALV3(38,s)) silently CORRUPTS: the scale-4 intermediate cannot hold 38 integer
     * digits (a single-row avg of 10^38-1 returns garbage) -> another avg-skip proof.
     */
    @Test
    fun pinAvgDecimal38IntermediateOverflowCorrupts() {
        val average = DorisTestCluster.queryScalar("SELECT avg(c_dec380) FROM p4_agg.probe_pin WHERE id = 1")
        assertThat(average).isNotNull().startsWith("-") // true value is +(10^38 - 1)
    }

    /**
     * avg(BIGINT) in Doris computes an EXACT wide integer sum before dividing: MAX+1 (a sum
     * that wraps `sum(BIGINT)` itself) averages to the true mean, and {2^53, 1, -2^53, 1}
     * averages to exactly 0.5. Trino 483 avg(bigint) accumulates in DOUBLE
     * (`BigintAverageAggregations`: order-dependent, a 1 added while 2^53 sits in the
     * accumulator is LOST). Divergent accumulation semantics -> avg(bigint) never pushed
     * (Trino-side texture in TestDorisP4Aggregates.testAvgBigintAccumulationDivergenceStaysLocal).
     */
    @Test
    fun pinAvgBigintIsExactWhereTrinoAccumulatesInDouble() {
        assertThat(DorisTestCluster.queryScalar("SELECT avg(c_big) FROM p4_agg.probe_pin"))
            .isEqualTo("4.611686018427388E18")
        assertThat(DorisTestCluster.queryScalar("SELECT avg(v) FROM p4_agg.probe_pin_avgb"))
            .isEqualTo("0.5")
    }

    /**
     * min/max over DOUBLE diverge: Doris treats NaN as the LARGEST value (max = NaN over
     * {NaN, Infinity, ...}) while Trino 483 max uses COMPARISON_UNORDERED_FIRST (NaN smallest
     * -> max = Infinity). REAL/DOUBLE min/max therefore stay local.
     */
    @Test
    fun pinDoubleMaxReturnsNanWhereTrinoReturnsInfinity() {
        assertThat(DorisTestCluster.queryScalar("SELECT max(c_double) FROM p4_agg.probe_pin")).isEqualTo("NaN")
        // min skips NaN on both engines, but the ±0.0 tie is order-dependent — recorded, not relied on
        assertThat(DorisTestCluster.queryScalar("SELECT min(c_double) FROM p4_agg.probe_pin WHERE id IN (3, 4)")).isEqualTo("-0.0")
    }

    /** NULL semantics: NULL group keys form one group; all-NULL groups aggregate like Trino. */
    @Test
    fun pinNullGroupingSemantics() {
        val groups = DorisTestCluster.querySingleColumn(
            "SELECT concat(coalesce(CAST(c_int AS STRING), 'NULL'), ':', CAST(count(*) AS STRING), ':', coalesce(CAST(sum(c_dec92) AS STRING), 'NULL')) " +
                "FROM p4_agg.probe_pin GROUP BY c_int ORDER BY c_int",
        )
        assertThat(groups).containsExactly("NULL:1:NULL", "1:2:3.30", "2:1:NULL")
        // count(col) over an all-NULL group is 0, min/max/sum are NULL (matches Trino)
        assertThat(DorisTestCluster.queryScalar("SELECT count(c_big) FROM p4_agg.probe_pin WHERE id IN (3, 4)")).isEqualTo("0")
        assertThat(DorisTestCluster.queryScalar("SELECT min(c_big) FROM p4_agg.probe_pin WHERE id IN (3, 4)")).isNull()
    }
}
