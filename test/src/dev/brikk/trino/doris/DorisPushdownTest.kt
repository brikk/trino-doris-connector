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
import io.trino.sql.planner.plan.PlanNode
import io.trino.testing.AbstractTestQueryFramework
import io.trino.testing.QueryRunner
import org.assertj.core.api.Assertions.assertThat

abstract class DorisPushdownTest(private val schema: String, private val provisionFixture: () -> Unit) : AbstractTestQueryFramework() {
    override fun createQueryRunner(): QueryRunner {
        provisionFixture()
        return DorisQueryRunner.builder().build()
    }

    protected open val pushdownSession: Session get() = session

    protected fun rows(session: Session, sql: String): List<String> =
        computeActual(session, sql).materializedRows.map { it.toString() }.sorted()

    protected fun assertPushedAndIdentical(sql: String, session: Session = pushdownSession) {
        assertThat(query(session, sql)).describedAs(sql).isFullyPushedDown()
        assertThat(rows(session, sql)).describedAs(sql).isEqualTo(rows(fullyLocalSession(session), sql))
    }

    protected fun assertLocalAndIdentical(sql: String, firstNode: Class<out PlanNode>, vararg nodes: Class<out PlanNode>) {
        assertThat(query(pushdownSession, sql)).describedAs(sql).isNotFullyPushedDown(firstNode, *nodes)
        assertThat(rows(pushdownSession, sql)).describedAs(sql).isEqualTo(rows(fullyLocalSession(pushdownSession), sql))
    }

    protected fun remoteStatements(session: Session, sql: String): List<String> {
        val execution = getDistributedQueryRunner().executeWithPlan(session, sql)
        return auditedStatements(execution.queryId(), schema)
    }

    protected fun remoteSql(session: Session, sql: String): String = remoteStatements(session, sql).single()

    protected fun remoteSql(sql: String): String = remoteSql(pushdownSession, sql)

    protected fun assertRemoteSqlContains(sql: String, expectedFragment: String) {
        assertThat(remoteSql(sql)).describedAs(sql).contains(expectedFragment)
    }
}
