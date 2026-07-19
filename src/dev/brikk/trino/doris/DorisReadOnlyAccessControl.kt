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
 * Connector-boundary write denial (PLAN G7.3; ledger §C): operations fail at analysis, before
 * any remote SQL is generated.
 *
 * Extends plugin-toolkit's [ReadOnlyAccessControl], which allows reads/metadata and denies
 * table/view/MV mutations explicitly; everything it does not override inherits the
 * [io.trino.spi.connector.ConnectorAccessControl] interface DEFAULTS, which DENY (verified at
 * 483: schema create/drop/rename, truncate, authorization/grants/roles, branches, functions,
 * table procedures all deny by default).
 *
 * [checkCanExecuteProcedure] is overridden because the interface default denies ALL
 * procedures, while PLAN §7 keeps the harmless metadata-cache flush available. `system.execute`
 * (Base JDBC's auto-installed raw-SQL escape hatch) is therefore denied here at the engine
 * boundary — and even if this layer were bypassed, [ReadOnlyDorisClient] denies the 1-arg
 * `getConnection` the procedure needs (PLAN G7.5).
 */
class DorisReadOnlyAccessControl : ReadOnlyAccessControl() {
    override fun checkCanExecuteProcedure(context: ConnectorSecurityContext, procedure: SchemaRoutineName) {
        val allowed = procedure.schemaName == "system" && procedure.routineName in ALLOWED_SYSTEM_PROCEDURES
        if (!allowed) {
            denyExecuteProcedure("$procedure (the Doris connector is read-only; only harmless metadata procedures are allowed)")
        }
    }

    companion object {
        /** PLAN §7: "harmless metadata-cache flush" remains available; nothing else. */
        private val ALLOWED_SYSTEM_PROCEDURES = setOf("flush_metadata_cache")
    }
}
