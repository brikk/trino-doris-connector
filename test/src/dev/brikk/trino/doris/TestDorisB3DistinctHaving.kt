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

/**
 * READ-ONLY-MAX batch 3: DISTINCT pushdown (incl. the VARCHAR grouping-key widening),
 * HAVING verification, and domain-compaction behavior. Probe findings in
 * `NOTES-readonly-max-batch3.md`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisB3DistinctHaving : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    private val noAggPushdown: Session
        get() = Session.builder(session)
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "aggregation_pushdown_enabled", "false")
            .build()

    private fun rows(session: Session, sql: String): List<String> =
        computeActual(session, sql).materializedRows.map { it.toString() }.sorted()

    private fun assertPushedAndIdentical(sql: String) {
        assertThat(query(sql)).describedAs(sql).isFullyPushedDown()
        assertThat(rows(session, sql)).describedAs(sql).isEqualTo(rows(noAggPushdown, sql))
    }

    private fun remoteSql(session: Session, sql: String): String {
        val execution = getDistributedQueryRunner().executeWithPlan(session, sql)
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        return DorisTestCluster.awaitAuditLogStatements(marker, 60_000).single { it.contains("b3_dist") }
    }

    private fun remoteSql(sql: String): String = remoteSql(session, sql)

    // --- item 1: DISTINCT ---

    @Test
    fun testDistinctPushesAsRemoteGroupBy() {
        // DISTINCT lowers to an aggregation with no aggregate functions; the grouping-key
        // gate decides pushability — one remote GROUP BY statement (probed shape).
        assertPushedAndIdentical("SELECT DISTINCT n, k FROM doris.b3_dist.t")
        assertThat(remoteSql("SELECT DISTINCT n, k FROM doris.b3_dist.t"))
            .contains("GROUP BY `n`, `k`")
        // composes with a pushed WHERE; LIMIT rides on top of the remote GROUP BY
        assertPushedAndIdentical("SELECT DISTINCT n FROM doris.b3_dist.t WHERE k > 5")
        assertThat(remoteSql("SELECT DISTINCT n FROM doris.b3_dist.t WHERE k > 5"))
            .contains("WHERE `k` > 5").contains("GROUP BY `n`")
        val limited = "SELECT DISTINCT n FROM doris.b3_dist.t LIMIT 3"
        assertThat(query(limited)).skipResultsCorrectnessCheckForPushdown().isFullyPushedDown()
        assertThat(remoteSql(limited)).contains("GROUP BY `n`").contains("LIMIT 3")
        assertThat(computeActual(limited).rowCount).isEqualTo(3)
    }

    @Test
    fun testStringDistinctAndGroupByNowPush() {
        // batch-3 widening: grouping is EQUALITY-only and string equality is byte-exact
        // (P4 probe) -> VARCHAR keys push. The unicode adversaries (ß vs ss, ü vs U,
        // NFC vs NFD) group as DISTINCT byte sequences on BOTH engines — differential-pinned.
        assertPushedAndIdentical("SELECT DISTINCT v FROM doris.b3_dist.t")
        assertThat(remoteSql("SELECT DISTINCT v FROM doris.b3_dist.t")).contains("GROUP BY `v`")
        assertPushedAndIdentical("SELECT v, count(*) FROM doris.b3_dist.t GROUP BY v")
        assertPushedAndIdentical("SELECT DISTINCT u FROM doris.b3_dist.adversaries")
        assertThat(computeActual("SELECT DISTINCT u FROM doris.b3_dist.adversaries").rowCount)
            .isEqualTo(6) // ß, ss, ü, U, NFC é, NFD é — six distinct byte sequences
        // NULL keys form one group on both engines
        assertPushedAndIdentical("SELECT v, count(*) FROM doris.b3_dist.nullkeys GROUP BY v")
        // CHAR keys still local: Doris groups stored bytes, Trino groups trimmed values
        val charKey = "SELECT c, count(*) FROM doris.b3_dist.charkeys GROUP BY c"
        assertThat(remoteSql(charKey)).doesNotContain("GROUP BY")
        assertThat(rows(session, charKey)).isEqualTo(rows(noAggPushdown, charKey))
    }

    // --- item 2: HAVING (verified, not built) ---

    @Test
    fun testHavingPushesAsOuterWhereOverTheAggregatedSubquery() {
        // HAVING needs no connector work at 483: the engine plans it as a filter over the
        // aggregation output, and since the pushed aggregation is a JdbcQueryRelationHandle,
        // the filter becomes a DOMAIN over the synthetic aggregate column — remote SQL shows
        // an outer WHERE over the GROUP BY subquery (probed shape, pinned here).
        val sql = "SELECT n, count(*) FROM doris.b3_dist.t GROUP BY n HAVING count(*) > 100"
        assertPushedAndIdentical(sql)
        assertThat(remoteSql(sql))
            .contains("count(*) AS `_pfgnrtd_").contains("GROUP BY `n`").containsPattern("WHERE `_pfgnrtd_\\d+` > 100")
    }

    // --- item 3: domain compaction ---

    @Test
    fun testDomainCompactionBehavior() {
        // small NON-ADJACENT IN pushes ENUMERATED (adjacent integers coalesce into ranges
        // at the ENGINE level before any connector involvement — hence the odd values)
        val smallIn = "SELECT id FROM doris.b3_dist.t WHERE n IN (1, 3, 5)"
        assertThat(query(smallIn)).isFullyPushedDown()
        assertThat(remoteSql(smallIn)).contains("`n` IN (1,3,5)")

        // above the threshold (base-jdbc `domain-compaction-threshold`, default 256) the
        // domain compacts to a RANGE remotely; correctness holds via the retained filter
        val bigValues = (0..499).joinToString { (it * 2).toString() }
        val bigIn = "SELECT id FROM doris.b3_dist.t WHERE id IN ($bigValues)"
        assertThat(remoteSql(bigIn)).contains("`id` >= 0").contains("`id` <= 998").doesNotContain("IN (")
        assertThat(rows(session, bigIn)).isEqualTo(rows(noAggPushdown, bigIn))

        // raising the SESSION knob makes the same IN push enumerated — the tuning story
        val wide = Session.builder(session)
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "domain_compaction_threshold", "1000")
            .build()
        assertThat(remoteSql(wide, bigIn)).contains("`id` IN (0,2,4")
        assertThat(rows(wide, bigIn)).isEqualTo(rows(session, bigIn))
    }

    @Test
    fun testDynamicFiltersReachTheRemoteScan() {
        // with join pushdown OFF (the default), the dim-side values flow to the probe-side
        // scan as a DYNAMIC FILTER domain in the remote SQL — the join-off alternative works
        val sql = "SELECT t.id FROM doris.b3_dist.t t JOIN doris.b3_dist.dim d ON t.k = d.k WHERE d.label = 'one'"
        val execution = getDistributedQueryRunner().executeWithPlan(session, sql)
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        val statements = DorisTestCluster.awaitAuditLogStatements(marker, 60_000).filter { it.contains("b3_dist") }
        val probeScan = statements.single { it.contains("`t`") && !it.contains("`dim`") }
        assertThat(probeScan).contains("`k` = 1") // the dim side's single k value, dynamically filtered
    }

    companion object {
        private fun provisionFixture() {
            DorisTestCluster.executeAsRoot(
                "DROP DATABASE IF EXISTS b3_dist",
                "CREATE DATABASE b3_dist",
                "CREATE TABLE b3_dist.t (id INT NOT NULL, n INT, v VARCHAR(30), k INT) " +
                    "DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES (\"replication_num\" = \"1\")",
                "INSERT INTO b3_dist.t SELECT number, number % 7, concat('v', number % 5), number % 11 FROM numbers('number' = '1000')",
                "CREATE TABLE b3_dist.adversaries (id INT NOT NULL, u VARCHAR(30)) " +
                    "DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES (\"replication_num\" = \"1\")",
                // ß vs ss, ü vs U, NFC vs NFD é — byte-distinct pairs that a collation-aware
                // grouping might merge; both engines must keep all six distinct
                "INSERT INTO b3_dist.adversaries VALUES (1, 'ß'), (2, 'ss'), (3, 'ü'), (4, 'U'), " +
                    "(5, 'é'), (6, concat('e', unhex('CC81'))), (7, 'ß'), (8, 'é')",
                "CREATE TABLE b3_dist.nullkeys (id INT NOT NULL, v VARCHAR(10)) " +
                    "DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES (\"replication_num\" = \"1\")",
                "INSERT INTO b3_dist.nullkeys VALUES (1, 'a'), (2, NULL), (3, NULL), (4, 'b')",
                "CREATE TABLE b3_dist.charkeys (id INT NOT NULL, c CHAR(5)) " +
                    "DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES (\"replication_num\" = \"1\")",
                "INSERT INTO b3_dist.charkeys VALUES (1, 'a'), (2, 'a '), (3, 'b')",
                "CREATE TABLE b3_dist.dim (k INT NOT NULL, label VARCHAR(20)) " +
                    "DUPLICATE KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES (\"replication_num\" = \"1\")",
                "INSERT INTO b3_dist.dim VALUES (1, 'one'), (2, 'two'), (3, 'three')",
            )
        }
    }
}
