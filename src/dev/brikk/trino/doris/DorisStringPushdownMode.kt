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

/** Doris compares, orders and matches strings as UTF-8 bytes; the modes differ in which hazards stay local. */
enum class DorisStringPushdownMode {
    /** Only `IS [NOT] NULL` is pushed for strings. */
    NULL_ONLY,

    /**
     * VARCHAR domains push fully, except values containing a 0x00 byte, for which Doris can
     * wrongly return no rows. CHAR pushes only null checks, because Doris compares stored bytes
     * while Trino ignores trailing spaces. VARCHAR TopN and LIKE stay local.
     */
    GUARDED,

    /**
     * Also pushes CHAR domains, VARCHAR TopN keys and LIKE, accepting that CHAR rows stored with
     * trailing spaces can be missed. Doris compares bytes by default, so this renders as [FULL].
     */
    BINARY,

    /** Same pushdown as [BINARY]; the caller asserts the workload is safe. */
    FULL,
    ;

    val allowsFullStringPushdown: Boolean
        get() = this == BINARY || this == FULL
}
