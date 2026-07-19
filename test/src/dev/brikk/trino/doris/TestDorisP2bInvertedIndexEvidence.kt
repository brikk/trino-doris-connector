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

import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.TimeUnit

/**
 * PLAN §6.4 inverted-index gates: (1) the generated remote SQL is the index-accelerable form,
 * (2) the predicate reaches the Doris scan node (EXPLAIN evidence). Doris optimizer INDEX
 * SELECTION and timings are recorded observations, NOT gates — they live in
 * `dev-docs/evidence/inverted-index-explain-p2b.md` (captured live 2026-07-19; profile counter
 * `RowsInvertedIndexFiltered` is the authoritative signal).
 *
 * The 1M-row `p2b_index.events` fixture (with an INVERTED index on the ARRAY<INT> column,
 * created via direct JDBC as root — the connector stays read-only) is persistent and cheap to
 * (re)build (~2s insert-select).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisP2bInvertedIndexEvidence : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionIndexFixture()
        return DorisQueryRunner.builder().build()
    }

    @Test
    fun testIndexAccelerableRemoteSqlReachesTheScanNode() {
        // Gate 1: correct emitted SQL — the BARE array_contains form (the `= 1` wrapper is
        // profile-proven to defeat the index; see the evidence doc) — and correct results.
        val sql = "SELECT count(*) FROM doris.p2b_index.events WHERE contains(tags, 7)"
        assertThat(query(sql)).matches("VALUES BIGINT '3003'")
        assertThat(query("SELECT id FROM doris.p2b_index.events WHERE contains(tags, 7)"))
            .isFullyPushedDown()

        val execution = getDistributedQueryRunner()
            .executeWithPlan(session, "SELECT id FROM doris.p2b_index.events WHERE contains(tags, 7)")
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        val statements = DorisTestCluster.awaitAuditLogStatements(marker, 60_000)
        val remoteSql = statements.single { it.contains("`events`") }
        assertThat(remoteSql).contains("(array_contains(`tags`, 7))")
        assertThat(remoteSql).doesNotContain("= 1")

        // Gate 2: the predicate reaches the Doris scan node (EXPLAIN of the exact remote form).
        val explain = explainRemote("SELECT `id` FROM `p2b_index`.`events` WHERE (array_contains(`tags`, 7))")
        assertThat(explain).contains("VOlapScanNode")
        assertThat(explain).containsPattern("PREDICATES:.*array_contains\\(tags")
    }

    private fun explainRemote(remoteSql: String): String {
        val process = ProcessBuilder(
            "docker", "exec", "trino-doris-fe",
            "mysql", "-h127.0.0.1", "-P9030", "-uroot", "-e", "EXPLAIN $remoteSql",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "docker exec EXPLAIN timed out" }
        return output
    }

    companion object {
        private const val EVENT_ROWS = 1_001_000L

        private fun provisionIndexFixture() {
            DorisFixtures.ensureBaseFixtures() // the index fixture is derived from p0_probe.nums

            val existing = runCatching { DorisTestCluster.queryScalar("SELECT COUNT(*) FROM p2b_index.events")?.toLong() }
                .getOrNull()
            if (existing == EVENT_ROWS) {
                return
            }
            DorisTestCluster.executeAsRoot(
                "CREATE DATABASE IF NOT EXISTS p2b_index",
                "DROP TABLE IF EXISTS p2b_index.events",
                """
                CREATE TABLE p2b_index.events (
                    id BIGINT NOT NULL,
                    tags ARRAY<INT> NOT NULL,
                    INDEX idx_tags (tags) USING INVERTED
                ) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num" = "1")
                """.trimIndent(),
                """
                INSERT INTO p2b_index.events
                SELECT n, ARRAY(CAST(n % 1000 AS INT), CAST((n + 7) % 1000 AS INT), CAST((n * 13) % 1000 AS INT))
                FROM p0_probe.nums
                """.trimIndent(),
            )
        }
    }
}
