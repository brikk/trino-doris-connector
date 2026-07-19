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

import com.google.inject.Inject
import io.trino.plugin.jdbc.DefaultQueryBuilder
import io.trino.plugin.jdbc.logging.RemoteQueryModifier

/**
 * The scan path applies its [RemoteQueryModifier] inside [DefaultQueryBuilder.prepareStatement]
 * (verified at 483 — NOT via `BaseJdbcClient.queryModifier`), and `RemoteQueryModifierModule`
 * binds the `RemoteQueryModifier` key non-optionally, so it cannot be rebound. This subclass is
 * the clean seam: it hands [DefaultQueryBuilder] the [DorisRemoteQueryModifier]-wrapped
 * modifier so every remote scan statement carries the Trino query id comment.
 */
class DorisQueryBuilder @Inject constructor(
    queryModifier: RemoteQueryModifier,
) : DefaultQueryBuilder(DorisRemoteQueryModifier(queryModifier))
