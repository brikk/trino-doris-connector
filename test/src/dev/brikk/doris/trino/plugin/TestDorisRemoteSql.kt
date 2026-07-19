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
    fun testStringEqualityIsAbsentFromRemoteSql() {
        // G5 negative proof at the wire: a string equality predicate must NOT appear in the
        // remote statement (NULL-only string domains; the Trino filter retains it).
        val execution = getDistributedQueryRunner()
            .executeWithPlan(session, "SELECT id FROM doris.p0_probe.scalars WHERE c_string = 'a normal string'")
        assertThat(execution.result().rowCount).isEqualTo(1)
        val marker = DorisRemoteQueryModifier.marker(execution.queryId().toString())
        val statements = DorisTestCluster.awaitAuditLogStatements(marker, AUDIT_LOG_TIMEOUT_MILLIS)
        val scanStatement = statements.single { it.contains("`scalars`") }
        assertThat(scanStatement).doesNotContain("a normal string")
    }

    companion object {
        private const val AUDIT_LOG_TIMEOUT_MILLIS = 60_000L
    }
}
