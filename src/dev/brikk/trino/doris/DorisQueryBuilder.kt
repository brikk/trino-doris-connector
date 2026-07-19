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
 * The scan path applies its [RemoteQueryModifier] inside [DefaultQueryBuilder.prepareStatement]
 * (verified at 483 — NOT via `BaseJdbcClient.queryModifier`), and `RemoteQueryModifierModule`
 * binds the `RemoteQueryModifier` key non-optionally, so it cannot be rebound. This subclass is
 * the clean seam: it hands [DefaultQueryBuilder] the [DorisRemoteQueryModifier]-wrapped
 * modifier so every remote scan statement carries the Trino query id comment.
 *
 * Also overrides the join-condition rendering for [JoinCondition.Operator.IDENTICAL]
 * (Trino `IS NOT DISTINCT FROM`): the upstream default renders the `≡` placeholder (no
 * database accepts it) and Doris REJECTS the `IS NOT DISTINCT FROM` syntax outright —
 * Doris's null-safe equality operator is `<=>`, live-proven truth-table-identical
 * (`NULL<=>NULL`=1, `NULL<=>1`=0; joins match NULL keys to each other exactly like Trino;
 * `NOTES-p5-joins.md`).
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
