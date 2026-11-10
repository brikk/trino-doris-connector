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

import io.trino.plugin.base.security.ReadOnlyAccessControl
import io.trino.spi.connector.ConnectorSecurityContext
import io.trino.spi.connector.SchemaRoutineName
import io.trino.spi.security.AccessDeniedException.denyExecuteProcedure

/**
 * Denies writes during analysis, before any SQL is generated. Checks that [ReadOnlyAccessControl]
 * does not override fall back to the [io.trino.spi.connector.ConnectorAccessControl] defaults,
 * which deny. Of the procedures only `system.flush_metadata_cache` is allowed; `system.execute`,
 * which runs raw SQL, is denied here and again in [ReadOnlyDorisClient].
 */
class DorisReadOnlyAccessControl : ReadOnlyAccessControl() {
    override fun checkCanExecuteProcedure(context: ConnectorSecurityContext, procedure: SchemaRoutineName) {
        val allowed = procedure.schemaName == "system" && procedure.routineName in ALLOWED_SYSTEM_PROCEDURES
        if (!allowed) {
            denyExecuteProcedure("$procedure (the Doris connector is read-only; only harmless metadata procedures are allowed)")
        }
    }

    companion object {
        private val ALLOWED_SYSTEM_PROCEDURES = setOf("flush_metadata_cache")
    }
}
