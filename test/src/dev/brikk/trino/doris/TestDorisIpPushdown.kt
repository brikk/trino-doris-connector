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
import io.trino.sql.planner.plan.FilterNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * IPADDRESS pushdown (unlocks IP `=`/IN/range domains, `contains`, `arrays_overlap`, and TopN).
 *
 * The safety argument (probe: `REPORT-ip-pushdown-probe-4.1.3.md`): Trino IPADDRESS is a single
 * 16-byte (IPv4-mapped) type, Doris has DISTINCT IPV4/IPV6 types. Byte ORDER is identical on
 * both engines (unsigned big-endian 16 bytes), so domains and TopN are exact once rendered in
 * the target dialect — IPV4 as dotted-quad (pushed only for v4-mapped values), IPV6 as
 * fully-expanded colon-hex. `array_contains` and `=` coerce a string needle to the column type;
 * `arrays_overlap` does NOT, so the constant-array form casts each element explicitly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisIpPushdown : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    private fun noPushdownSession(): Session = Session.builder(session)
        .setSystemProperty("complex_expression_pushdown", "false")
        .build()

    // --- domain pushdown: =, IN, range, IS NULL ---

    @Test
    fun testIpV4DomainPushdown() {
        assertPushedAndEquivalent("v4 = IPADDRESS '192.168.1.1'")
        assertPushedAndEquivalent("v4 IN (IPADDRESS '10.0.0.5', IPADDRESS '255.255.255.255')")
        assertPushedAndEquivalent("v4 > IPADDRESS '10.0.0.0' AND v4 < IPADDRESS '200.0.0.0'")
        assertPushedAndEquivalent("v4 IS NULL")
        assertPushedAndEquivalent("v4 IS NOT NULL")
    }

    @Test
    fun testIpV6DomainPushdown() {
        assertPushedAndEquivalent("v6 = IPADDRESS '2001:db8::ff00:42:8329'")
        assertPushedAndEquivalent("v6 = IPADDRESS '::ffff:192.168.1.1'") // v4-mapped stored in a v6 column
        assertPushedAndEquivalent("v6 IN (IPADDRESS '::', IPADDRESS 'ffff::')")
        assertPushedAndEquivalent("v6 > IPADDRESS '::' AND v6 < IPADDRESS 'ffff::'")
        assertPushedAndEquivalent("v6 IS NULL")
    }

    @Test
    fun testIpV4NonV4MappedLiteralStaysLocal() {
        // A real IPv6 literal can never equal an IPV4 column value; Trino IPADDRESS lets you
        // write it, but it is not renderable as a Doris IPV4 literal -> kept local, still correct
        // (matches nothing).
        assertLocalAndEquivalent("v4 = IPADDRESS '2001:db8::1'")
        // a mixed IN list with one non-v4-mapped value keeps the whole domain local
        assertLocalAndEquivalent("v4 IN (IPADDRESS '10.0.0.5', IPADDRESS '2001:db8::1')")
    }

    // --- contains ---

    @Test
    fun testIpContainsPushdown() {
        assertPushedAndEquivalent("contains(a4, IPADDRESS '192.168.1.1')")
        assertPushedAndEquivalent("contains(a4, IPADDRESS '10.0.0.5')") // row 4 has a NULL element too
        assertPushedAndEquivalent("contains(a6, IPADDRESS '::ffff:192.168.1.1')")
        assertPushedAndEquivalent("contains(a6, IPADDRESS 'ffff::')")
        // non-v4-mapped needle against a v4 array: can never match, kept local
        assertLocalAndEquivalent("contains(a4, IPADDRESS '2001:db8::1')")
    }

    // --- arrays_overlap: column×column and column×constant ---

    @Test
    fun testIpArraysOverlapPushdown() {
        assertPushedAndEquivalent("arrays_overlap(a4, b4)")
        assertPushedAndEquivalent("arrays_overlap(a4, ARRAY[IPADDRESS '192.168.1.1', IPADDRESS '10.0.0.5'])")
        assertPushedAndEquivalent("arrays_overlap(a6, ARRAY[IPADDRESS 'ffff::'])")
        assertPushedAndEquivalent("arrays_overlap(ARRAY[IPADDRESS '10.0.0.5'], a4)") // flipped
        // empty/all-null stays local
        assertLocalAndEquivalent("arrays_overlap(a4, ARRAY[CAST(NULL AS IPADDRESS)])")
    }

    // --- TopN ---

    @Test
    fun testIpTopNPushesAndOrdersByteExact() {
        for (col in listOf("v4", "v6")) {
            val sql = "SELECT id FROM doris.ip_pushdown.t ORDER BY $col ASC NULLS FIRST, id LIMIT 10"
            assertThat(query(sql)).isFullyPushedDown()
            val pushed = computeActual(sql).onlyColumn.toList()
            val local = computeActual(noPushdownSession(), sql).onlyColumn.toList()
            assertThat(pushed).describedAs("TopN $col").isEqualTo(local)
        }
    }

    // --- remote SQL shapes ---

    @Test
    fun testRemoteSqlShapes() {
        assertRemoteSqlContains(
            "SELECT id FROM doris.ip_pushdown.t WHERE v4 = IPADDRESS '192.168.1.1'",
            "'192.168.1.1'",
        )
        assertRemoteSqlContains(
            "SELECT id FROM doris.ip_pushdown.t WHERE contains(a4, IPADDRESS '192.168.1.1')",
            "(array_contains(`a4`, '192.168.1.1'))",
        )
        assertRemoteSqlContains(
            "SELECT id FROM doris.ip_pushdown.t WHERE arrays_overlap(a4, ARRAY[IPADDRESS '10.0.0.5'])",
            "(arrays_overlap(array_filter(x -> x IS NOT NULL, `a4`), ARRAY(CAST('10.0.0.5' AS IPV4))))",
        )
        assertRemoteSqlContains(
            "SELECT id FROM doris.ip_pushdown.t WHERE arrays_overlap(a6, ARRAY[IPADDRESS 'ffff::'])",
            "ARRAY(CAST('ffff:0:0:0:0:0:0:0' AS IPV6))",
        )
    }

    // --- helpers ---

    private fun assertPushedAndEquivalent(predicate: String) {
        val sql = "SELECT id FROM doris.ip_pushdown.t WHERE $predicate"
        assertThat(query(sql)).isFullyPushedDown()
        val pushed = computeActual("$sql ORDER BY id").onlyColumn.toList()
        val local = computeActual(noPushdownSession(), "$sql ORDER BY id").onlyColumn.toList()
        assertThat(pushed).describedAs(predicate).isEqualTo(local)
    }

    private fun assertLocalAndEquivalent(predicate: String) {
        val sql = "SELECT id FROM doris.ip_pushdown.t WHERE $predicate"
        assertThat(query(sql)).isNotFullyPushedDown(FilterNode::class.java)
        val withRules = computeActual("$sql ORDER BY id").onlyColumn.toList()
        val local = computeActual(noPushdownSession(), "$sql ORDER BY id").onlyColumn.toList()
        assertThat(withRules).describedAs(predicate).isEqualTo(local)
    }

    private fun assertRemoteSqlContains(sql: String, expectedFragment: String) {
        val execution = getDistributedQueryRunner().executeWithPlan(session, sql)
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        val statements = DorisTestCluster.awaitAuditLogStatements(marker, AUDIT_LOG_TIMEOUT_MILLIS)
        val scan = statements.single { it.contains("`t`") || it.contains("ip_pushdown") }
        assertThat(scan).describedAs(sql).contains(expectedFragment)
    }

    companion object {
        private const val AUDIT_LOG_TIMEOUT_MILLIS = 60_000L

        private fun provisionFixture() {
            DorisTestCluster.executeAsRoot(
                "DROP DATABASE IF EXISTS ip_pushdown",
                "CREATE DATABASE ip_pushdown",
                """
                CREATE TABLE ip_pushdown.t (
                    id INT NOT NULL,
                    v4 IPV4, v6 IPV6,
                    a4 ARRAY<IPV4>, b4 ARRAY<IPV4>, a6 ARRAY<IPV6>
                ) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                INSERT INTO ip_pushdown.t VALUES
                (1, '0.0.0.0', '::', ARRAY('0.0.0.0','10.0.0.1'), ARRAY('192.168.1.1'), ARRAY('::','::1')),
                (2, '192.168.1.1', '2001:db8::ff00:42:8329', ARRAY('192.168.1.1'), ARRAY('8.8.8.8'), ARRAY('2001:db8::ff00:42:8329')),
                (3, '255.255.255.255', 'ffff::', ARRAY('255.255.255.255'), ARRAY('255.255.255.255'), ARRAY('ffff::')),
                (4, '10.0.0.5', '::ffff:192.168.1.1', ARRAY(NULL,'10.0.0.5'), ARRAY('10.0.0.5'), ARRAY('::ffff:192.168.1.1')),
                (5, NULL, NULL, NULL, NULL, NULL),
                (6, '10.0.0.1', '::1', ARRAY(), ARRAY(), ARRAY())
                """.trimIndent(),
            )
        }
    }
}
