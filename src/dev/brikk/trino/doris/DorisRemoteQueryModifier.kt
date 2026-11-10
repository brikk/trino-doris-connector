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
 * Appends the Trino query id as a comment to each remote statement, after any configured
 * `query.comment-format`, so the Doris audit log can be searched by query id. The id is checked
 * against a strict alphabet so it cannot close the comment.
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
