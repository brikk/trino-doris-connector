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
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * P2a live differential suite: for every allowlisted ARRAY element type, the Trino SELECT of
 * the array column is compared element-by-element against the Doris oracle
 * (`array_size(col)` / `CAST(element_at(col, k) AS STRING)` over direct JDBC — the spike's
 * oracle pattern; the decoder's own parse is never treated as truth).
 *
 * Fixtures live in the test-owned `p2_array` database (recreated per run); `p0_array_spike`
 * is never touched.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisP2aArray : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    // --- type surface ---

    @Test
    fun testDescribeShowsNativeArrayTypes() {
        val columns = computeActual("DESCRIBE doris.p2_array.num").materializedRows
            .associate { it.getField(0) as String to it.getField(1) as String }
        assertThat(columns).isEqualTo(
            mapOf(
                "id" to "integer",
                "a_tinyint" to "array(tinyint)",
                "a_smallint" to "array(smallint)",
                "a_int" to "array(integer)",
                "a_bigint" to "array(bigint)",
                "a_largeint" to "array(decimal(38,0))",
                "a_dec92" to "array(decimal(9,2))",
                "a_dec3810" to "array(decimal(38,10))",
                "a_float" to "array(real)",
                "a_double" to "array(double)",
                "a_boolean" to "array(boolean)",
            ),
        )
        val temporal = computeActual("DESCRIBE doris.p2_array.temporal").materializedRows
            .associate { it.getField(0) as String to it.getField(1) as String }
        assertThat(temporal).isEqualTo(
            mapOf(
                "id" to "integer",
                "a_date" to "array(date)",
                "a_dt0" to "array(timestamp(0))",
                "a_dt3" to "array(timestamp(3))",
                "a_dt6" to "array(timestamp(6))",
            ),
        )
        val ip = computeActual("DESCRIBE doris.p2_array.ip").materializedRows
            .associate { it.getField(0) as String to it.getField(1) as String }
        assertThat(ip).isEqualTo(
            mapOf("id" to "integer", "a_ipv4" to "array(ipaddress)", "a_ipv6" to "array(ipaddress)"),
        )
        val nested = computeActual("DESCRIBE doris.p2_array.nested").materializedRows
            .associate { it.getField(0) as String to it.getField(1) as String }
        assertThat(nested).isEqualTo(
            mapOf(
                "id" to "integer",
                "a2" to "array(array(integer))",
                "a3" to "array(array(array(integer)))",
            ),
        )
    }

    // --- differential vs the Doris oracle, per allowlisted element type ---

    @Test
    fun testNumericArraysMatchOracle() {
        for (column in listOf("a_tinyint", "a_smallint", "a_int", "a_bigint", "a_largeint", "a_dec92", "a_dec3810")) {
            assertArrayColumnMatchesOracle("num", column, ::compareExactText)
        }
    }

    @Test
    fun testBooleanArraysMatchOracle() {
        assertArrayColumnMatchesOracle("num", "a_boolean") { trino, oracle ->
            val canonical = when (trino) {
                null -> null
                true -> "1"
                false -> "0"
                else -> "unexpected: $trino"
            }
            assertThat(canonical).isEqualTo(oracle)
        }
    }

    @Test
    fun testFloatAndDoubleArraysMatchOracle() {
        // Approximate types compare numerically (Doris's text renderings differ from Java's);
        // Float/Double.equals treats NaN == NaN and preserves the F3 Infinity boundary artifact.
        assertArrayColumnMatchesOracle("num", "a_float") { trino, oracle ->
            assertThat(trino as Float?).isEqualTo(oracle?.toFloat())
        }
        assertArrayColumnMatchesOracle("num", "a_double") { trino, oracle ->
            assertThat(trino as Double?).isEqualTo(oracle?.toDouble())
        }
    }

    @Test
    fun testTemporalArraysMatchOracle() {
        assertArrayColumnMatchesOracle("temporal", "a_date", ::compareExactText)
        assertArrayColumnMatchesOracle("temporal", "a_dt0") { trino, oracle -> compareTimestamp(trino, oracle, 0) }
        assertArrayColumnMatchesOracle("temporal", "a_dt3") { trino, oracle -> compareTimestamp(trino, oracle, 3) }
        assertArrayColumnMatchesOracle("temporal", "a_dt6") { trino, oracle -> compareTimestamp(trino, oracle, 6) }
    }

    @Test
    fun testIpArraysMatchOracle() {
        assertArrayColumnMatchesOracle("ip", "a_ipv4", ::compareExactText)
        assertArrayColumnMatchesOracle("ip", "a_ipv6", ::compareExactText)
    }

    // --- nesting (>=3 levels) and boundary rows ---

    @Test
    fun testNestedArrays() {
        assertThat(query("SELECT a2 FROM doris.p2_array.nested WHERE id = 1"))
            .matches("VALUES CAST(ARRAY[ARRAY[1, NULL], NULL, ARRAY[]] AS array(array(integer)))")
        assertThat(query("SELECT a3 FROM doris.p2_array.nested WHERE id = 1"))
            .matches("VALUES CAST(ARRAY[ARRAY[ARRAY[1, 2], ARRAY[3]], ARRAY[ARRAY[4]]] AS array(array(array(integer))))")
        assertThat(query("SELECT a2, a3 FROM doris.p2_array.nested WHERE id = 2"))
            .matches("VALUES (CAST(NULL AS array(array(integer))), CAST(NULL AS array(array(array(integer)))))")
    }

    @Test
    fun testEmptyVersusNullVersusNullElement() {
        // F1: the three shapes are distinct end-to-end.
        assertThat(query("SELECT a_int FROM doris.p2_array.num WHERE id = 3"))
            .matches("VALUES CAST(ARRAY[] AS array(integer))")
        assertThat(query("SELECT a_int FROM doris.p2_array.num WHERE id = 4"))
            .matches("VALUES CAST(NULL AS array(integer))")
        assertThat(query("SELECT a_int FROM doris.p2_array.num WHERE id = 2"))
            .matches("VALUES CAST(ARRAY[1, NULL, 3] AS array(integer))")
    }

    @Test
    fun testDoubleMaxBoundaryRowSurfacesInfinityFaithfully() {
        // F3: Doris renders DOUBLE max with 16 sig digits; it reparses to Infinity. Surfaced
        // faithfully, matching the oracle (which suffers the same wire artifact) — never a
        // silently-wrong finite value, never a scan failure.
        assertThat(query("SELECT a_double FROM doris.p2_array.num WHERE id = 5"))
            .matches("VALUES CAST(ARRAY[infinity(), -infinity()] AS array(double))")
        assertThat(query("SELECT a_float FROM doris.p2_array.num WHERE id = 5"))
            .matches("VALUES CAST(ARRAY[nan()] AS array(real))")
    }

    @Test
    fun testLargeintElementBeyondDecimal38FailsLoud() {
        // The ±(2^127−1) LARGEINT extremes exceed DECIMAL(38,0): the query FAILS, never clamps
        // (ledger §A LARGEINT rule; spike §7.4).
        assertQueryFails(
            "SELECT a_largeint FROM doris.p2_array.large_extreme",
            ".*LARGEINT value out of DECIMAL\\(38, 0\\) range.*",
        )
    }

    // --- string-family arrays: NOT native (spike F4 ambiguity) ---

    @Test
    fun testStringArraysAreNotExposedNatively() {
        // Default policy: hidden entirely.
        assertThat(
            computeActual("DESCRIBE doris.p2_array.strs").materializedRows.map { it.getField(0) },
        ).containsExactly("id")

        // The crafted ambiguity pair (spike §4): one element `a", "b` vs two elements `a`,`b`
        // produce BYTE-IDENTICAL wire text. A native decode would have to silently guess; the
        // connector refuses instead. Under CONVERT_TO_VARCHAR the column is plain TEXT of the
        // whole array — both rows return the identical rendering, and no element-count claim
        // is ever made, so a silent mis-read is impossible.
        val convertSession: Session = Session.builder(getSession())
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "unsupported_type_handling", "CONVERT_TO_VARCHAR")
            .build()
        val text = computeActual(convertSession, "SELECT a_vc FROM doris.p2_array.strs ORDER BY id").onlyColumn.toList()
        assertThat(text).containsExactly("""["a", "b"]""", """["a", "b"]""")
        // ... while the Doris oracle proves the two rows are semantically DIFFERENT:
        assertThat(DorisTestCluster.queryScalar("SELECT array_size(a_vc) FROM p2_array.strs WHERE id = 1")).isEqualTo("1")
        assertThat(DorisTestCluster.queryScalar("SELECT array_size(a_vc) FROM p2_array.strs WHERE id = 2")).isEqualTo("2")
    }

    // --- differential machinery ---

    private fun assertArrayColumnMatchesOracle(table: String, column: String, compare: (Any?, String?) -> Unit) {
        val trinoRows = computeActual("SELECT id, $column FROM doris.p2_array.$table ORDER BY id").materializedRows
        assertThat(trinoRows).isNotEmpty()
        for (row in trinoRows) {
            val id = (row.getField(0) as Number).toInt()
            val cell = row.getField(1)
            val where = "$table.$column id=$id"
            val oracleSize = DorisTestCluster.queryScalar("SELECT array_size($column) FROM p2_array.$table WHERE id = $id")
            if (oracleSize == null) {
                assertThat(cell).describedAs("$where (oracle: NULL array)").isNull()
                continue
            }
            assertThat(cell).describedAs(where).isInstanceOf(List::class.java)
            val values = cell as List<*>
            assertThat(values.size).describedAs("$where size").isEqualTo(oracleSize.toInt())
            for (position in 1..values.size) {
                val oracleElement = DorisTestCluster.queryScalar(
                    "SELECT CAST(element_at($column, $position) AS STRING) FROM p2_array.$table WHERE id = $id",
                )
                try {
                    compare(values[position - 1], oracleElement)
                } catch (e: AssertionError) {
                    throw AssertionError("$where element=$position: ${e.message}", e)
                }
            }
        }
    }

    /** Exact-text kinds: integers, LARGEINT/DECIMAL (plain string preserves scale), DATE, IPADDRESS. */
    private fun compareExactText(trino: Any?, oracle: String?) {
        val canonical = when (trino) {
            null -> null
            is BigDecimal -> trino.toPlainString()
            else -> trino.toString()
        }
        assertThat(canonical).isEqualTo(oracle)
    }

    private fun compareTimestamp(trino: Any?, oracle: String?, precision: Int) {
        val canonical = when (trino) {
            null -> null
            is LocalDateTime -> trino.format(timestampFormatter(precision))
            else -> trino.toString() // SqlTimestamp-style toString already renders "uuuu-MM-dd HH:mm:ss[.frac]"
        }
        assertThat(canonical).isEqualTo(oracle)
    }

    private fun timestampFormatter(precision: Int): DateTimeFormatter {
        val fraction = if (precision == 0) "" else "." + "S".repeat(precision)
        return DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss$fraction")
    }

    companion object {
        @Suppress("LongMethod")
        private fun provisionFixture() {
            DorisTestCluster.executeAsRoot(
                "DROP DATABASE IF EXISTS p2_array",
                "CREATE DATABASE p2_array",
                """
                CREATE TABLE p2_array.num (
                    id INT NOT NULL,
                    a_tinyint ARRAY<TINYINT>, a_smallint ARRAY<SMALLINT>, a_int ARRAY<INT>, a_bigint ARRAY<BIGINT>,
                    a_largeint ARRAY<LARGEINT>, a_dec92 ARRAY<DECIMALV3(9, 2)>, a_dec3810 ARRAY<DECIMALV3(38, 10)>,
                    a_float ARRAY<FLOAT>, a_double ARRAY<DOUBLE>, a_boolean ARRAY<BOOLEAN>
                ) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                INSERT INTO p2_array.num VALUES
                (1, ARRAY(-128, 127), ARRAY(-32768, 32767), ARRAY(-2147483648, 2147483647),
                    ARRAY(-9223372036854775808, 9223372036854775807),
                    ARRAY(CAST('99999999999999999999999999999999999999' AS LARGEINT),
                          CAST('-99999999999999999999999999999999999999' AS LARGEINT)),
                    ARRAY(1.1, -9999999.99),
                    ARRAY(CAST('1.0000000000' AS DECIMALV3(38, 10)),
                          CAST('1234567890123456789012345678.0123456789' AS DECIMALV3(38, 10))),
                    ARRAY(3.14, CAST('3.402823e38' AS FLOAT), CAST('-3.402823e38' AS FLOAT)),
                    ARRAY(2.718281828459045, -0.5),
                    ARRAY(true, false)),
                (2, ARRAY(1, NULL, 3), ARRAY(1, NULL, 3), ARRAY(1, NULL, 3), ARRAY(1, NULL, 3),
                    ARRAY(NULL, CAST('-1' AS LARGEINT)),
                    ARRAY(NULL, 0.01), ARRAY(NULL, CAST('0.0000000001' AS DECIMALV3(38, 10))),
                    ARRAY(NULL, 1.5), ARRAY(NULL, 2.5), ARRAY(NULL, true, false)),
                (3, ARRAY(), ARRAY(), ARRAY(), ARRAY(), ARRAY(), ARRAY(), ARRAY(), ARRAY(), ARRAY(), ARRAY()),
                (4, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
                (5, ARRAY(0), ARRAY(0), ARRAY(0), ARRAY(0), ARRAY(CAST('0' AS LARGEINT)), ARRAY(0.0), ARRAY(0.0),
                    ARRAY(CAST('nan' AS FLOAT)),
                    ARRAY(CAST('1.7976931348623157e308' AS DOUBLE), CAST('-1.7976931348623157e308' AS DOUBLE)),
                    ARRAY(true))
                """.trimIndent(),
                """
                CREATE TABLE p2_array.temporal (
                    id INT NOT NULL,
                    a_date ARRAY<DATE>, a_dt0 ARRAY<DATETIME>, a_dt3 ARRAY<DATETIME(3)>, a_dt6 ARRAY<DATETIME(6)>
                ) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                INSERT INTO p2_array.temporal VALUES
                (1, ARRAY('2021-06-15', '2000-02-29'), ARRAY('2021-06-15 12:34:56'),
                    ARRAY('2021-06-15 12:34:56.789'), ARRAY('2021-06-15 12:34:56.789012')),
                (2, ARRAY('0000-01-01', '9999-12-31'), ARRAY('0000-01-01 00:00:00', '9999-12-31 23:59:59'),
                    ARRAY('0000-01-01 00:00:00.000', '9999-12-31 23:59:59.999'),
                    ARRAY('0000-01-01 00:00:00.000000', '9999-12-31 23:59:59.999999')),
                (3, ARRAY(NULL, '2021-01-01'), ARRAY(NULL), ARRAY(NULL), ARRAY(NULL)),
                (4, NULL, NULL, NULL, NULL)
                """.trimIndent(),
                """
                CREATE TABLE p2_array.ip (id INT NOT NULL, a_ipv4 ARRAY<IPV4>, a_ipv6 ARRAY<IPV6>)
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                INSERT INTO p2_array.ip VALUES
                (1, ARRAY('192.168.1.1', '0.0.0.0', '255.255.255.255'),
                    ARRAY('2001:db8::ff00:42:8329', '::', 'fe80::1')),
                (2, ARRAY(NULL, '10.0.0.255'), ARRAY(NULL, 'ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff')),
                (3, ARRAY(), ARRAY()),
                (4, NULL, NULL)
                """.trimIndent(),
                """
                CREATE TABLE p2_array.nested (id INT NOT NULL, a2 ARRAY<ARRAY<INT>>, a3 ARRAY<ARRAY<ARRAY<INT>>>)
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                INSERT INTO p2_array.nested VALUES
                (1, ARRAY(ARRAY(1, NULL), NULL, ARRAY()),
                    ARRAY(ARRAY(ARRAY(1, 2), ARRAY(3)), ARRAY(ARRAY(4)))),
                (2, NULL, NULL)
                """.trimIndent(),
                """
                CREATE TABLE p2_array.strs (id INT NOT NULL, a_vc ARRAY<VARCHAR(50)>)
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                // spike §4 ambiguity pair: one element `a", "b` vs two elements `a`,`b`
                """INSERT INTO p2_array.strs VALUES (1, ARRAY('a", "b')), (2, ARRAY('a', 'b'))""",
                """
                CREATE TABLE p2_array.large_extreme (id INT NOT NULL, a_largeint ARRAY<LARGEINT>)
                DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                INSERT INTO p2_array.large_extreme VALUES
                (1, ARRAY(CAST('170141183460469231731687303715884105727' AS LARGEINT)))
                """.trimIndent(),
            )
        }
    }
}
