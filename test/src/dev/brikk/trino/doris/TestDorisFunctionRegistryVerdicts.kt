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

import dev.brikk.house.sql.metadata.HazardRegistry
import dev.brikk.house.sql.metadata.HazardVerdict
import dev.brikk.house.sql.metadata.HazardVerdict.CONDITIONALLY_EQUIVALENT
import dev.brikk.house.sql.metadata.HazardVerdict.DIVERGENT
import dev.brikk.house.sql.metadata.HazardVerdict.IDENTICAL
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/**
 * The brikk hazard registry entries the pushdown rules were proven against. A changed registry
 * does not change what the connector pushes; it fails this test so the rule can be re-verified.
 */
class TestDorisFunctionRegistryVerdicts {
    private data class Pin(val source: String, val target: String, val verdict: HazardVerdict, val provenance: String)

    @Test
    fun testRegistryMatchesThePinnedEntries() {
        for (pin in PINS) {
            val entry = HazardRegistry.lookup("trino", "doris", pin.source)
            assertThat(entry).describedAs(pin.source).isNotNull
            assertThat(entry!!.sourceName).describedAs(pin.source).isEqualTo(pin.source)
            assertThat(entry.targetName).describedAs(pin.source).isEqualTo(pin.target)
            assertThat(entry.verdict).describedAs(pin.source).isEqualTo(pin.verdict)
            assertThat(entry.provenance).describedAs(pin.source).isEqualTo(pin.provenance)
        }
    }

    @Test
    fun testRegistryArtifactIsThePinnedJar() {
        val jar = File(HazardRegistry::class.java.protectionDomain.codeSource.location.toURI())
        assertThat(jar.name).isEqualTo("brikk-sql-metadata-jvmMain-0.21.0.jar")
        val digest = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
        assertThat(digest.joinToString("") { "%02x".format(it) }).isEqualTo(ARTIFACT_SHA256)
    }

    companion object {
        private const val ARTIFACT_SHA256 = "506ea02a67bbf983122a978fb3fb0657ff6d606a801820b0e247aa509f5678b2"

        private const val BATCH7_ARRAY = "REPORT-doris-differential-probe-2026-07-13.md#batch7-array"
        private const val BATCH2 = "REPORT-doris-differential-probe-2026-07-13.md#batch2"
        private const val BATCH6_DATETIME = "REPORT-doris-differential-probe-2026-07-13.md#batch6-datetime"

        private val PINS = listOf(
            Pin("contains", "array_contains", DIVERGENT, BATCH7_ARRAY),
            Pin("arrays_overlap", "arrays_overlap", CONDITIONALLY_EQUIVALENT, BATCH7_ARRAY),
            Pin("array_position", "array_position", IDENTICAL, BATCH7_ARRAY),
            Pin("cardinality", "array_size", DIVERGENT, BATCH7_ARRAY),
            Pin("date", "date", IDENTICAL, BATCH6_DATETIME),
            Pin("date_trunc", "date_trunc", CONDITIONALLY_EQUIVALENT, BATCH6_DATETIME),
            Pin("year", "year", IDENTICAL, BATCH6_DATETIME),
            Pin("month", "month", IDENTICAL, BATCH6_DATETIME),
            Pin("day", "day", IDENTICAL, BATCH6_DATETIME),
            Pin("hour", "hour", IDENTICAL, BATCH6_DATETIME),
            Pin("minute", "minute", IDENTICAL, BATCH6_DATETIME),
            Pin("second", "second", IDENTICAL, BATCH6_DATETIME),
            Pin("coalesce", "coalesce", IDENTICAL, BATCH2),
            Pin("nullif", "nullif", IDENTICAL, BATCH2),
            Pin("length", "length", DIVERGENT, BATCH2),
            Pin("starts_with", "starts_with", CONDITIONALLY_EQUIVALENT, "REPORT-doris-differential-probe-2026-07-13.md#batch4-string"),
        )
    }
}
