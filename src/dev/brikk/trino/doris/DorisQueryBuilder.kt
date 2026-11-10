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

import com.google.inject.Inject
import io.trino.plugin.jdbc.DefaultQueryBuilder
import io.trino.plugin.jdbc.JdbcClient
import io.trino.plugin.jdbc.JdbcJoinCondition
import io.trino.plugin.jdbc.logging.RemoteQueryModifier
import io.trino.spi.connector.JoinCondition

/**
 * Scans apply the [RemoteQueryModifier] given to [DefaultQueryBuilder], not
 * `BaseJdbcClient.queryModifier`, and the modifier binding cannot be replaced, so the query-id
 * comment is added here.
 *
 * IS NOT DISTINCT FROM renders as Doris's `<=>`: the upstream default emits a `≡` placeholder,
 * and Doris rejects the `IS NOT DISTINCT FROM` syntax.
 */
class DorisQueryBuilder @Inject constructor(
    queryModifier: RemoteQueryModifier,
) : DefaultQueryBuilder(DorisRemoteQueryModifier(queryModifier)) {
    override fun formatJoinCondition(
        client: JdbcClient,
        leftRelationAlias: String,
        rightRelationAlias: String,
        condition: JdbcJoinCondition,
    ): String {
        if (condition.operator == JoinCondition.Operator.IDENTICAL) {
            val left = buildJoinColumn(client, condition.leftColumn)
            val right = buildJoinColumn(client, condition.rightColumn)
            return "$leftRelationAlias.$left <=> $rightRelationAlias.$right"
        }
        return super.formatJoinCondition(client, leftRelationAlias, rightRelationAlias, condition)
    }
}
