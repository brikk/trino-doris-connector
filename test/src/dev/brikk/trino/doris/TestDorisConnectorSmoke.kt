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
import io.trino.sql.planner.plan.AggregationNode
import io.trino.sql.planner.plan.FilterNode
import io.trino.sql.planner.plan.TopNNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisConnectorSmoke : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionP1SmokeFixture()
        return DorisQueryRunner.builder().build()
    }


    @Test
    fun testShowSchemasShowsProbeDatabasesAndHidesSystemSchemas() {
        val schemas = computeActual("SHOW SCHEMAS FROM doris").onlyColumnAsSet
        assertThat(schemas).contains("p0_probe", "p1_smoke")
        // Trino synthesizes information_schema, so only the native system schemas are hidden.
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
            // Doris reports BOOLEAN as tinyint(1), distinct from native TINYINT's tinyint(4).
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
        // String-family array leaves have ambiguous wire text; opaque engine states have no readable value mapping.
        assertThat(describedColumnNames("doris.p0_probe.arrays")).isEqualTo(
            setOf<Any>(
                "id", "a_int", "a_bigint", "a_largeint", "a_float", "a_double",
                "a_dec9_2", "a_date", "a_dt6", "a_boolean", "a_array_int",
            ),
        )
        assertThat(describedColumnNames("doris.p0_probe.mapstruct")).isEqualTo(setOf<Any>("id"))
        assertThat(describedColumnNames("doris.p0_probe.opaque")).isEqualTo(setOf<Any>("id"))
        // ... and scanning such a table still works on the visible columns.
        assertThat(query("SELECT count(*) FROM doris.p0_probe.opaque")).matches("VALUES BIGINT '2'")
    }

    @Test
    fun testConvertToVarcharExposesComplexWireTextButNeverOpaqueStates() {
        val session: Session = Session.builder(getSession())
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "unsupported_type_handling", "CONVERT_TO_VARCHAR")
            .build()
        val arrayColumns = computeActual(session, "DESCRIBE doris.p0_probe.arrays").materializedRows
            .associate { it.getField(0) as String to it.getField(1) as String }
        assertThat(arrayColumns["a_int"]).isEqualTo("array(integer)") // allowlisted: native even under the policy
        assertThat(arrayColumns["a_varchar50"]).isEqualTo("varchar")
        assertThat(arrayColumns["a_string"]).isEqualTo("varchar")
        assertThat(computeActual(session, "SELECT a_varchar50 FROM doris.p0_probe.arrays WHERE id = 1").onlyValue)
            .isEqualTo("""["a", "b", "c"]""")
        assertThat(computeActual(session, "SELECT m_si FROM doris.p0_probe.mapstruct WHERE id = 1").rowCount).isEqualTo(1)
        assertThat(computeActual(session, "DESCRIBE doris.p0_probe.opaque").materializedRows.map { it.getField(0) })
            .containsExactly("id")
    }

    @Test
    fun testViewIsExposed() {
        assertThat(query("SELECT count(*) FROM doris.p0_probe.scalars_view")).matches("VALUES BIGINT '8'")
    }


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
        // Native LARGEINT extremes have 39 digits and cannot fit Trino's DECIMAL(38,0).
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
        assertThat(query("SELECT c_dec76_10 FROM doris.p0_probe.scalars WHERE id = 1"))
                .matches("VALUES VARCHAR '123456789012345678901234567890.1234567890'")
    }

    @Test
    fun testDecimal256TextNeverUsesRemoteNumericSemantics() {
        val binary = Session.builder(session)
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "string_pushdown_mode", "BINARY")
            .build()

        // Trino compares the exposed wire text lexicographically; Doris would compare the
        // native DECIMAL numerically. These values are deliberately divergent under `> '5'`.
        val range = "SELECT id FROM doris.p0_probe.scalars WHERE c_dec76_10 > '5'"
        for (testSession in listOf(session, binary)) {
            assertThat(computeActual(testSession, range).onlyColumn.toList()).containsExactly(3)
            assertThat(query(testSession, range)).isNotFullyPushedDown(FilterNode::class.java)
        }

        // Different textual scale: unequal in Trino, numerically equal in Doris.
        val equality = "SELECT id FROM doris.p0_probe.scalars " +
            "WHERE c_dec76_10 = '123456789012345678901234567890.123456789'"
        assertThat(computeActual(equality).rowCount).isZero()
        assertThat(query(equality)).isNotFullyPushedDown(FilterNode::class.java)

        // BINARY normally enables VARCHAR TopN, but Decimal256 is text only at the Trino seam.
        val topN = "SELECT c_dec76_10 FROM doris.p0_probe.scalars " +
            "ORDER BY c_dec76_10 ASC NULLS LAST LIMIT 3"
        assertThat(query(binary, topN)).ordered().isNotFullyPushedDown(TopNNode::class.java)
        assertThat(computeActual(binary, topN).onlyColumn.toList()).containsExactly(
            "-99999999999999999999999999999999999999999999999999999999999999999.9999999999",
            "123456789012345678901234567890.1234567890",
            "99999999999999999999999999999999999999999999999999999999999999999.9999999999",
        )

        val groupBy = "SELECT c_dec76_10, count(*) FROM doris.p0_probe.scalars GROUP BY c_dec76_10"
        assertThat(query(groupBy)).isNotFullyPushedDown(AggregationNode::class.java)
    }

    @Test
    fun testFloatAndDoubleRoundTrip() {
        assertThat(query("SELECT c_float, c_double FROM doris.p1_smoke.scalars WHERE id = 1"))
                .matches("VALUES (REAL '1.5', DOUBLE '2.718281828459045')")
    }

    @Test
    fun testDoubleInfinityRowsReadInsteadOfPoisoningTheScan() {
        // Doris renders finite DOUBLE extrema with too few digits; the wire text reparses as Infinity.
        assertThat(query("SELECT id, c_double FROM doris.p0_probe.scalars WHERE id IN (2, 3)"))
                .matches("VALUES (2, -infinity()), (3, infinity())")
        assertThat(query("SELECT count(*) FROM doris.p0_probe.scalars WHERE id = 6 AND c_double = infinity()"))
                .matches("VALUES BIGINT '1'")
    }

    @Test
    fun testDateEdgesIncludingYearZero() {
        // JDBC getDate rejects year zero; text transport preserves it.
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
        assertThat(query("SELECT count(*) FROM doris.p1_smoke.scalars WHERE id = 2 AND c_varchar100 = '' AND c_varchar100 IS NOT NULL"))
                .matches("VALUES BIGINT '1'")
    }

    @Test
    fun testNullRow() {
        val columns = listOf(
            "c_boolean", "c_tinyint", "c_smallint", "c_int", "c_bigint", "c_largeint", "c_float", "c_double",
            "c_dec9_2", "c_dec38_10", "c_date", "c_dt0", "c_dt6", "c_char10", "c_varchar100", "c_string", "c_json",
        )
        val row = computeActual("SELECT ${columns.joinToString()} FROM doris.p1_smoke.scalars WHERE id = 3").materializedRows.single()
        columns.forEachIndexed { index, column -> assertThat(row.getField(index)).describedAs(column).isNull() }
    }

    @Test
    fun testJsonAndVariantRoundTrip() {
        // 484 preserves the Doris wire object's member order instead of sorting keys.
        assertThat(query("SELECT json_format(c_json) FROM doris.p0_probe.scalars WHERE id = 1"))
                .matches("""VALUES VARCHAR '{"k":"v","n":42,"arr":[1,2,3]}'""")
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


    @Test
    fun testNumericDatePredicatesAreFullyPushedDown() {
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_int > 0")).isFullyPushedDown()
        assertThat(query("SELECT n FROM doris.p0_probe.nums WHERE n > 1000995")).isFullyPushedDown()
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_date > DATE '2020-01-01'")).isFullyPushedDown()
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_boolean = true")).isFullyPushedDown()
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_dt6 = TIMESTAMP '2021-06-15 12:34:56.789012'")).isFullyPushedDown()
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_largeint = DECIMAL '99999999999999999999999999999999999999'"))
            .isFullyPushedDown()
    }

    @Test
    fun testStringPredicateContractUnderDefaultGuardedMode() {
        // Fully pushed: varchar/string equality + ranges (and null-ness, exact as always).
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_varchar100 = 'héllo 中文 🦆'"))
            .isFullyPushedDown()
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_string > 'a'")).isFullyPushedDown()
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_varchar100 IS NULL")).isFullyPushedDown()
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_varchar100 IS NOT NULL")).isFullyPushedDown()
        // Superset pre-filter: LIKE ships its prefix range but the exact LIKE stays local.
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_string LIKE 'a p%'"))
            .isNotFullyPushedDown(FilterNode::class.java)
        // Hazards stay local: CHAR (trailing-space divergence) and 0x00-bearing literals.
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_char10 = 'char10'"))
            .isNotFullyPushedDown(FilterNode::class.java)
        assertThat(query("SELECT id FROM doris.p1_smoke.scalars WHERE c_varchar100 = U&'a\\0000b'"))
            .isNotFullyPushedDown(FilterNode::class.java)
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
        assertThat(computeActual("SELECT n FROM doris.p0_probe.nums LIMIT 5").rowCount).isEqualTo(5)
        assertThat(query("SELECT n FROM doris.p0_probe.nums LIMIT 5"))
            .skipResultsCorrectnessCheckForPushdown()
            .isFullyPushedDown()
    }

    private fun describedColumnNames(table: String): Set<Any?> {
        return computeActual("DESCRIBE $table").materializedRows.map { it.getField(0) }.toSet()
    }

    companion object {
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
