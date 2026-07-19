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

/**
 * FE audit-log proof of the pushed remote SQL (ledger §E: `fe.audit.log` `Stmt=` shows the
 * remote statement verbatim). [DorisRemoteQueryModifier] appends the Trino query id comment to
 * every remote statement, making the audit lookup deterministic per Trino query (PLAN §8).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDorisRemoteSql : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner = DorisQueryRunner.builder().build()

    @Test
    fun testPushedDomainRemoteSqlIsObservableInAuditLog() {
        val execution = getDistributedQueryRunner()
            .executeWithPlan(session, "SELECT n FROM doris.p0_probe.nums WHERE n > 1000995")
        assertThat(execution.result().rowCount).isEqualTo(4)

        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        // The FE audit logger flushes asynchronously (multi-second lag observed live).
        val statements = DorisTestCluster.awaitAuditLogStatements(marker, AUDIT_LOG_TIMEOUT_MILLIS)
        val scanStatement = statements.single { it.contains("`nums`") }

        // Domain pushdown produced the remote WHERE, and the scan carries the query-id comment.
        assertThat(scanStatement).contains("SELECT `n` FROM")
        assertThat(scanStatement).contains("1000995")
        assertThat(scanStatement).contains(marker)
    }

    @Test
    fun testStringEqualityWireShapePerPushdownMode() {
        // G5 at the wire, mode-resolved (REPORT-string-comparison-probe-4.1.3.md): under the
        // GUARDED default the string equality DOES ship as a superset pre-filter (the exact
        // Trino filter is retained locally); under NULL_ONLY it must NOT appear at all.
        val sql = "SELECT id FROM doris.p0_probe.scalars WHERE c_string = 'a normal string'"

        val guarded = getDistributedQueryRunner().executeWithPlan(session, sql)
        assertThat(guarded.result().rowCount).isEqualTo(1)
        val guardedRemote = DorisTestCluster
            .awaitAuditLogStatements(DorisRemoteQueryModifier.marker(guarded.queryId().toString()), AUDIT_LOG_TIMEOUT_MILLIS)
            .single { it.contains("`scalars`") }
        assertThat(guardedRemote).contains("a normal string")

        val nullOnlySession = io.trino.Session.builder(session)
            .setCatalogSessionProperty(DorisQueryRunner.CATALOG, "string_pushdown_mode", "NULL_ONLY")
            .build()
        val nullOnly = getDistributedQueryRunner().executeWithPlan(nullOnlySession, sql)
        assertThat(nullOnly.result().rowCount).isEqualTo(1)
        val nullOnlyRemote = DorisTestCluster
            .awaitAuditLogStatements(DorisRemoteQueryModifier.marker(nullOnly.queryId().toString()), AUDIT_LOG_TIMEOUT_MILLIS)
            .single { it.contains("`scalars`") }
        assertThat(nullOnlyRemote).doesNotContain("a normal string")
    }

    companion object {
        private const val AUDIT_LOG_TIMEOUT_MILLIS = 60_000L
    }
}
