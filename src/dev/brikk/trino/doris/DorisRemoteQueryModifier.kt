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

import io.trino.plugin.jdbc.logging.RemoteQueryModifier
import io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR
import io.trino.spi.TrinoException
import io.trino.spi.connector.ConnectorSession

/**
 * Appends the Trino query id to every remote statement as a safe SQL comment
 * (PLAN §4.1 `DorisRemoteQueryModifier` / §8 observability), rendered as
 * `... [slash][star]trino_query_id=20260719_021530_00003_abcde[star][slash]`.
 *
 * This makes the Doris FE audit log (`fe.audit.log` `Stmt=`) deterministically greppable by
 * Trino query id — the observability substrate for pushdown-correctness proofs (ledger §E) and
 * for the cancellation test's `information_schema.processlist` lookups.
 *
 * Composes with (rather than replaces) the modifier bound by base-jdbc's
 * `RemoteQueryModifierModule`, so a user-configured `query.comment-format` still applies.
 * The query id is validated against a strict alphabet — comment-breakout is impossible, and an
 * unexpected id shape fails loud rather than being silently dropped.
 */
class DorisRemoteQueryModifier(
    private val delegate: RemoteQueryModifier,
) : RemoteQueryModifier {
    override fun apply(session: ConnectorSession, query: String): String {
        val queryId = session.queryId
        if (!QUERY_ID_PATTERN.matches(queryId)) {
            throw TrinoException(GENERIC_INTERNAL_ERROR, "Unexpected Trino query id shape (refusing to render SQL comment): '$queryId'")
        }
        return delegate.apply(session, query) + " /*trino_query_id=$queryId*/"
    }

    companion object {
        private val QUERY_ID_PATTERN = Regex("[a-zA-Z0-9_]+")

        fun marker(queryId: String): String = "trino_query_id=$queryId"
    }
}
