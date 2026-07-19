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
package dev.brikk.doris.trino.plugin

import io.trino.Session
import io.trino.sql.planner.plan.FilterNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * P1a smoke suite against the live stock Doris 4.1.3 compose cluster (port 9130).
 *
 * Reads the pre-existing `p0_probe` fixtures (NEVER mutated) and provisions its own
 * `p1_smoke` database for controlled round-trip rows. Type expectations are the
 * ledger §A contract, verbatim.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisP1aSmoke : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionP1SmokeFixture()
        return DorisQueryRunner.builder().build()
    }

    // --- metadata: schemas / tables / types ---

    @Test
    fun testShowSchemasShowsProbeDatabasesAndHidesSystemSchemas() {
        val schemas = computeActual("SHOW SCHEMAS FROM doris").onlyColumnAsSet
        assertThat(schemas).contains("p0_probe", "p1_smoke")
        // G9: Doris system schemas are hidden. (Trino synthesizes its own information_schema
        // per catalog, so that name is legitimately present at the Trino level.)
        assertThat(schemas).doesNotContain("mysql", "__internal_schema")
    }

    @Test
    fun testShowTables() {
        val tables = computeActual("SHOW TABLES FROM doris.p0_probe").onlyColumnAsSet
        assertThat(tables).contains("scalars", "nums", "opaque", "arrays", "mapstruct", "scalars_view")
    }

    @Test
    fun testDescribeScalarsSurfacesLedgerTypeContract() {
        val actual = computeActual("DESCRIBE doris.p0_probe.scalars").materializedRows
            .associate { it.getField(0) as String to it.getField(1) as String }
        val expected = mapOf(
            "id" to "integer",
            // COLUMN_TYPE tinyint(1) == Doris BOOLEAN; tinyint(4) == real TINYINT (PROBE Impl #7)
            "c_boolean" to "boolean",
            "c_tinyint" to "tinyint",
            "c_smallint" to "smallint",
            "c_int" to "integer",
            "c_bigint" to "bigint",
            "c_largeint" to "decimal(38,0)",
            "c_float" to "real",
            "c_double" to "double",
            "c_dec9_2" to "decimal(9,2)",
            "c_dec38_10" to "decimal(38,10)",
            // Decimal256: exact text via VARCHAR, not native DECIMAL (Trino ceiling is 38)
            "c_dec76_10" to "varchar",
            "c_date" to "date",
            "c_dt0" to "timestamp(0)",
            "c_dt3" to "timestamp(3)",
            "c_dt6" to "timestamp(6)",
            "c_char10" to "char(10)",
            "c_varchar100" to "varchar(100)",
            "c_string" to "varchar",
            "c_json" to "json",
            "c_ipv4" to "ipaddress",
            "c_ipv6" to "ipaddress",
            "c_variant" to "json",
        )
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun testUnsupportedColumnsAreHiddenNotErroring() {
        // arrays: every ARRAY column hidden until P2; mapstruct: MAP/STRUCT deferred;
        // opaque: BITMAP/HLL/AGG_STATE are opaque engine states (ledger §A).
        assertThat(describedColumnNames("doris.p0_probe.arrays")).isEqualTo(setOf<Any>("id"))
        assertThat(describedColumnNames("doris.p0_probe.mapstruct")).isEqualTo(setOf<Any>("id"))
        assertThat(describedColumnNames("doris.p0_probe.opaque")).isEqualTo(setOf<Any>("id"))
        // ... and scanning such a table still works on the visible columns.
        assertThat(query("SELECT count(*) FROM doris.p0_probe.opaque")).matches("VALUES BIGINT '2'")
    }

    @Test
    fun testConvertToVarcharExposesComplexWireTextButNeverOpaqueStates() {
        // unsupported-type-handling=CONVERT_TO_VARCHAR is honored exactly where the ledger
        // permits VARCHAR-of-wire-text (ARRAY/MAP/STRUCT); opaque engine states stay hidden.
        val session: Session = Session.builder(getSession())
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "unsupported_type_handling", "CONVERT_TO_VARCHAR")
            .build()
        val arrayColumns = computeActual(session, "DESCRIBE doris.p0_probe.arrays").materializedRows
            .associate { it.getField(0) as String to it.getField(1) as String }
        assertThat(arrayColumns["a_int"]).isEqualTo("varchar")
        assertThat(arrayColumns["a_varchar50"]).isEqualTo("varchar")
        assertThat(computeActual(session, "SELECT a_int FROM doris.p0_probe.arrays WHERE id = 1").onlyValue)
            .isEqualTo("[1, 2, 3]")
        assertThat(computeActual(session, "SELECT m_si FROM doris.p0_probe.mapstruct WHERE id = 1").rowCount).isEqualTo(1)
        // BITMAP/HLL/AGG_STATE remain hidden under every policy (ledger §A; PROBE Impl #9).
        assertThat(computeActual(session, "DESCRIBE doris.p0_probe.opaque").materializedRows.map { it.getField(0) })
            .containsExactly("id")
    }

    @Test
    fun testViewIsExposed() {
        assertThat(query("SELECT count(*) FROM doris.p0_probe.scalars_view")).matches("VALUES BIGINT '8'")
    }

    // --- scalar value round-trips (p1_smoke controlled fixture) ---

    @Test
    fun testIntegerAndBooleanRoundTrip() {
        assertThat(query("SELECT c_boolean, c_tinyint, c_smallint, c_int, c_bigint FROM doris.p1_smoke.scalars WHERE id = 1"))
                .matches("VALUES (true, TINYINT '127', SMALLINT '32767', 2147483647, BIGINT '9223372036854775807')")
        assertThat(query("SELECT c_boolean, c_tinyint, c_smallint, c_int, c_bigint FROM doris.p1_smoke.scalars WHERE id = 2"))
                .matches("VALUES (false, TINYINT '-128', SMALLINT '-32768', -2147483648, BIGINT '-9223372036854775808')")
    }

    @Test
    fun testLargeintInRangeRoundTrip() {
        assertThat(query("SELECT id, c_largeint FROM doris.p1_smoke.scalars WHERE id IN (1, 2)"))
                .matches("VALUES (1, DECIMAL '99999999999999999999999999999999999999'), (2, DECIMAL '-99999999999999999999999999999999999999')")
    }

    @Test
    fun testLargeintOutOfDecimal38RangeFailsLoud() {
        // p0_probe.scalars rows 1-3 hold the LARGEINT extremes +/-(2^127-1) — 39 digits, outside
        // DECIMAL(38,0). Reading them must FAIL, never clamp (ledger §A LARGEINT rule).
        assertQueryFails(
            "SELECT c_largeint FROM doris.p0_probe.scalars WHERE id = 1",
            ".*LARGEINT value out of DECIMAL\\(38, 0\\) range.*",
        )
    }

    @Test
    fun testDecimalRoundTrip() {
        assertThat(query("SELECT c_dec9_2, c_dec38_10 FROM doris.p1_smoke.scalars WHERE id = 1"))
                .matches("VALUES (DECIMAL '1234567.89', DECIMAL '1234567890123456789012345678.0123456789')")
    }

    @Test
    fun testDecimal256ReadsExactTextAsVarchar() {
        // Decimal256 wire text is exact; the VARCHAR mapping preserves it verbatim (PROBE Impl #3).
        assertThat(query("SELECT c_dec76_10 FROM doris.p0_probe.scalars WHERE id = 1"))
                .matches("VALUES VARCHAR '123456789012345678901234567890.1234567890'")
    }

    @Test
    fun testFloatAndDoubleRoundTrip() {
        assertThat(query("SELECT c_float, c_double FROM doris.p1_smoke.scalars WHERE id = 1"))
                .matches("VALUES (REAL '1.5', DOUBLE '2.718281828459045')")
    }

    @Test
    fun testDoubleInfinityRowsReadInsteadOfPoisoningTheScan() {
        // Stock behavior: getDouble/getObject THROW on the Infinity wire value and one poisoned
        // row kills the scan (PROBE Impl #6; STOCK). The getString read surfaces them faithfully.
        // Note rows 2/3 hold Doris-rendered DOUBLE max (16 significant digits), which reparses to
        // +/-Infinity — the documented wire-boundary caveat (ARRAY F3 scalar twin).
        assertThat(query("SELECT id, c_double FROM doris.p0_probe.scalars WHERE id IN (2, 3)"))
                .matches("VALUES (2, -infinity()), (3, infinity())")
        assertThat(query("SELECT count(*) FROM doris.p0_probe.scalars WHERE id = 6 AND c_double = infinity()"))
                .matches("VALUES BIGINT '1'")
    }

    @Test
    fun testDateEdgesIncludingYearZero() {
        // getDate/getObject throw SQLException YEAR on 0000-01-01; the getString read is exact
        // (PROBE Impl #6; ledger §A row date).
        assertThat(query("SELECT id, c_date FROM doris.p0_probe.scalars WHERE id IN (2, 3)"))
                .matches("VALUES (2, DATE '0000-01-01'), (3, DATE '9999-12-31')")
    }

    @Test
    fun testDatetimePrecisionAndZonelessRead() {
        assertThat(query("SELECT c_dt0, c_dt3, c_dt6 FROM doris.p0_probe.scalars WHERE id = 1"))
                .matches("VALUES (TIMESTAMP '2021-06-15 12:34:56', TIMESTAMP '2021-06-15 12:34:56.789', TIMESTAMP '2021-06-15 12:34:56.789012')")
        // Zero-date minimum reads via getObject(LocalDateTime) (getTimestamp throws YEAR).
        assertThat(query("SELECT c_dt6 FROM doris.p0_probe.scalars WHERE id = 2"))
                .matches("VALUES TIMESTAMP '0000-01-01 00:00:00.000000'")
        assertThat(query("SELECT c_dt6 FROM doris.p0_probe.scalars WHERE id = 3"))
                .matches("VALUES TIMESTAMP '9999-12-31 23:59:59.999999'")
    }

    @Test
    fun testCharVarcharStringRoundTripIncludingUnicodeAndEmptyString() {
        assertThat(query("SELECT c_char10, c_varchar100, c_string FROM doris.p1_smoke.scalars WHERE id = 1"))
                .matches("VALUES (CAST('abc' AS char(10)), CAST('héllo 中文 🦆' AS varchar(100)), VARCHAR 'a plain string')")
        // Empty string is distinct from NULL (PROBE §3).
        assertThat(query("SELECT count(*) FROM doris.p1_smoke.scalars WHERE id = 2 AND c_varchar100 = '' AND c_varchar100 IS NOT NULL"))
                .matches("VALUES BIGINT '1'")
    }

    @Test
    fun testNullRow() {
        assertThat(
            query(
                """
                SELECT count(*) FROM doris.p1_smoke.scalars
                WHERE id = 3
                  AND c_boolean IS NULL AND c_tinyint IS NULL AND c_smallint IS NULL AND c_int IS NULL
                  AND c_bigint IS NULL AND c_largeint IS NULL AND c_float IS NULL AND c_double IS NULL
                  AND c_dec9_2 IS NULL AND c_dec38_10 IS NULL AND c_date IS NULL AND c_dt0 IS NULL
                  AND c_dt6 IS NULL AND c_char10 IS NULL AND c_varchar100 IS NULL AND c_string IS NULL
                  AND c_json IS NULL
                """.trimIndent(),
            ),
        ).matches("VALUES BIGINT '1'")
    }

    @Test
    fun testJsonAndVariantRoundTrip() {
        assertThat(query("SELECT json_format(c_json) FROM doris.p0_probe.scalars WHERE id = 1"))
                .matches("""VALUES VARCHAR '{"arr":[1,2,3],"k":"v","n":42}'""")
        assertThat(query("SELECT json_format(c_variant) FROM doris.p0_probe.scalars WHERE id = 4"))
                .matches("""VALUES VARCHAR '{"deep":{"a":[1,{"b":"中文"}]}}'""")
    }

    @Test
    fun testIpAddressRoundTrip() {
        assertThat(query("SELECT id, CAST(c_ipv4 AS varchar), CAST(c_ipv6 AS varchar) FROM doris.p0_probe.scalars WHERE id <= 4"))
            .matches(
                """
                VALUES
                    (1, VARCHAR '192.168.1.1', VARCHAR '2001:db8::ff00:42:8329'),
                    (2, VARCHAR '0.0.0.0', VARCHAR '::'),
                    (3, VARCHAR '255.255.255.255', VARCHAR 'ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff'),
                    (4, VARCHAR '10.0.0.255', VARCHAR 'fe80::1')
                """.trimIndent(),
            )
    }

    // --- pushdown posture (P1a: exact numeric/date/boolean domains only; strings NULL-only) ---

    @Test
    fun testNumericDatePredicatesAreFullyPushedDown() {
        // P1b hook: additionally assert the verbatim remote SQL via the FE audit log
        // (fe.audit.log Stmt= is the proven observability substrate, ledger §E).
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_int > 0")).isFullyPushedDown()
        assertThat(query("SELECT n FROM doris.p0_probe.nums WHERE n > 1000995")).isFullyPushedDown()
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_date > DATE '2020-01-01'")).isFullyPushedDown()
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_boolean = true")).isFullyPushedDown()
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_dt6 = TIMESTAMP '2021-06-15 12:34:56.789012'")).isFullyPushedDown()
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_largeint = DECIMAL '99999999999999999999999999999999999999'"))
            .isFullyPushedDown()
    }

    @Test
    fun testStringPredicatesAreNotPushedDown() {
        // G5: no string equality/range pushdown until collation is proven; the Trino filter
        // must remain. Null-ness pushdown is exact and allowed.
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_varchar100 = 'héllo 中文 🦆'"))
            .isNotFullyPushedDown(FilterNode::class.java)
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_string LIKE 'a p%'"))
            .isNotFullyPushedDown(FilterNode::class.java)
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_varchar100 IS NULL")).isFullyPushedDown()
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_varchar100 IS NOT NULL")).isFullyPushedDown()
    }

    @Test
    fun testApproximateTypePredicatesAreNotPushedDown() {
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_double = DOUBLE '2.718281828459045'"))
            .isNotFullyPushedDown(FilterNode::class.java)
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_float = REAL '1.5'"))
            .isNotFullyPushedDown(FilterNode::class.java)
    }

    @Test
    fun testLimitIsPushedDown() {
        // LIMIT pushdown is an inherited stock baseline proven against Doris 4.1.3 (STOCK; ledger §E).
        assertThat(computeActual("SELECT n FROM doris.p0_probe.nums LIMIT 5").rowCount).isEqualTo(5)
        assertThat(query("SELECT n FROM doris.p0_probe.nums LIMIT 5"))
            .skipResultsCorrectnessCheckForPushdown()
            .isFullyPushedDown()
    }

    private fun describedColumnNames(table: String): Set<Any?> {
        return computeActual("DESCRIBE $table").materializedRows.map { it.getField(0) }.toSet()
    }

    companion object {
        /**
         * Provisions the `p1_smoke` fixture database directly over JDBC. `p0_probe` /
         * `p0_array_spike` are never mutated. Recreated from scratch on every run.
         */
        private fun provisionP1SmokeFixture() {
            DorisQueryRunner.openDirectConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP DATABASE IF EXISTS p1_smoke")
                    statement.execute("CREATE DATABASE p1_smoke")
                    statement.execute(
                        """
                        CREATE TABLE p1_smoke.scalars (
                            id INT NOT NULL,
                            c_boolean BOOLEAN,
                            c_tinyint TINYINT,
                            c_smallint SMALLINT,
                            c_int INT,
                            c_bigint BIGINT,
                            c_largeint LARGEINT,
                            c_float FLOAT,
                            c_double DOUBLE,
                            c_dec9_2 DECIMALV3(9, 2),
                            c_dec38_10 DECIMALV3(38, 10),
                            c_date DATE,
                            c_dt0 DATETIME,
                            c_dt6 DATETIME(6),
                            c_char10 CHAR(10),
                            c_varchar100 VARCHAR(100),
                            c_string STRING,
                            c_json JSON
                        )
                        DUPLICATE KEY(id)
                        DISTRIBUTED BY HASH(id) BUCKETS 1
                        PROPERTIES ("replication_num" = "1")
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO p1_smoke.scalars VALUES
                        (1, true, 127, 32767, 2147483647, 9223372036854775807,
                         CAST('99999999999999999999999999999999999999' AS LARGEINT),
                         1.5, 2.718281828459045,
                         CAST('1234567.89' AS DECIMALV3(9, 2)),
                         CAST('1234567890123456789012345678.0123456789' AS DECIMALV3(38, 10)),
                         '2021-06-15', '2021-06-15 12:34:56', '2021-06-15 12:34:56.789012',
                         'abc', 'héllo 中文 🦆', 'a plain string', '{"k":"v","n":42}'),
                        (2, false, -128, -32768, -2147483648, -9223372036854775808,
                         CAST('-99999999999999999999999999999999999999' AS LARGEINT),
                         -1.5, -2.5,
                         CAST('-9999999.99' AS DECIMALV3(9, 2)),
                         CAST('-1.0000000001' AS DECIMALV3(38, 10)),
                         '0000-01-01', '0000-01-01 00:00:00', '0000-01-01 00:00:00.000000',
                         '', '', '', '[]'),
                        (3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                         NULL, NULL, NULL, NULL, NULL, NULL, NULL)
                        """.trimIndent(),
                    )
                }
            }
        }
    }
}
