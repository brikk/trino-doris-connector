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
import io.trino.spi.TrinoException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/**
 * brikk evidence drift suite (PLAN G3/§6.3, the P2 "registry drift test" exit criterion —
 * unblocked by the released `brikk-sql-metadata-jvm:0.7.0`). No cluster needed.
 *
 * The connector's pinned tuples in [DorisPushdownEvidence] are the source of truth; this
 * suite proves (a) the released registry still agrees with every pin, (b) the G3
 * verdict-gating policy is structurally satisfied, (c) registry presence alone never creates
 * a rule (canaries), and (d) the exact artifact is on the classpath (version + jar sha256) —
 * an artifact swap fails visibly here even if its data happens to agree.
 */
class TestDorisPushdownEvidenceDrift {
    // --- (a) every registered rule's pin matches the released registry exactly ---

    @Test
    fun testEveryRulePinMatchesTheRegistry() {
        // contains, arrays_overlap, array_position, json_extract_scalar, cardinality
        assertThat(DorisPushdownEvidence.ALL).hasSize(5)
        for (evidence in DorisPushdownEvidence.ALL) {
            val entry = DorisPushdownEvidence.lookup(evidence.registrySource)
            assertThat(entry).describedAs(evidence.registrySource).isNotNull
            assertThat(entry!!.verdict).describedAs(evidence.registrySource).isEqualTo(evidence.expectedVerdict)
            assertThat(entry.sourceName).describedAs(evidence.registrySource).isEqualTo(evidence.registrySource)
            assertThat(entry.targetName).describedAs(evidence.registrySource).isEqualTo(evidence.expectedTarget)
            assertThat(entry.provenance).describedAs(evidence.registrySource).isEqualTo(evidence.expectedProvenance)
        }
        // ... which is exactly what the connector-construction gate enforces:
        assertThatCode { DorisPushdownEvidence.verifyAgainstRegistry() }.doesNotThrowAnyException()
    }

    @Test
    fun testConstructionGateFailsLoudOnDrift() {
        // A drifted pin must refuse connector construction with a clear message.
        val drifted = DorisPushdownEvidence.CONTAINS.copy(expectedVerdict = HazardVerdict.IDENTICAL)
        assertThatThrownBy { DorisPushdownEvidence.verify(drifted) }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("verdict drifted")
            .hasMessageContaining("Refusing to construct the Doris connector")
        val missing = DorisPushdownEvidence.CONTAINS.copy(registrySource = "no_such_function")
        assertThatThrownBy { DorisPushdownEvidence.verify(missing) }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("no trino->doris registry entry")
        val wrongTarget = DorisPushdownEvidence.ARRAY_POSITION.copy(expectedTarget = "array_pos")
        assertThatThrownBy { DorisPushdownEvidence.verify(wrongTarget) }
            .isInstanceOf(TrinoException::class.java)
            .hasMessageContaining("targetName drifted")
    }

    // --- (b) G3 verdict-gating policy, structurally ---

    @Test
    fun testVerdictGatingPolicyIsStructurallySatisfied() {
        val eligible = setOf(HazardVerdict.IDENTICAL, HazardVerdict.CONDITIONALLY_EQUIVALENT, HazardVerdict.DIVERGENT)
        for (evidence in DorisPushdownEvidence.ALL) {
            // NO_EQUIVALENT / UNCLEAR / unknown are never pushable (G3).
            assertThat(evidence.expectedVerdict).describedAs(evidence.registrySource).isIn(eligible)
            when (evidence.expectedVerdict) {
                HazardVerdict.IDENTICAL ->
                    assertThat(evidence.treatment).isEqualTo(DorisPushdownEvidence.Treatment.DIRECT)
                HazardVerdict.CONDITIONALLY_EQUIVALENT -> {
                    // "requires a connector rule with explicit guards or wrappers" — the guard
                    // must be declared AND present in the rendering the rule emits.
                    assertThat(evidence.treatment).isEqualTo(DorisPushdownEvidence.Treatment.GUARDED_WRAPPER)
                    assertThat(evidence.guard).describedAs(evidence.registrySource).isNotNull()
                    assertThat(evidence.dorisRendering).contains(evidence.guard)
                }
                HazardVerdict.DIVERGENT -> {
                    // "may be implemented as a separate explicit rewrite after a dedicated
                    // live test" — the live test must be named and must exist on the classpath.
                    assertThat(evidence.treatment).isEqualTo(DorisPushdownEvidence.Treatment.CONNECTOR_ORIGINAL_REWRITE)
                }
                else -> error("unreachable")
            }
            // the rendering uses the pinned target function, and the live proof class exists
            assertThat(evidence.dorisRendering).contains(evidence.expectedTarget)
            assertThatCode { Class.forName("dev.brikk.trino.doris.${evidence.liveProof}") }
                .describedAs("live proof class ${evidence.liveProof}")
                .doesNotThrowAnyException()
        }
    }

    // --- (c) canaries: registry presence alone never creates a rule ---

    @Test
    fun testDeliberatelyUnpushedFunctionsHaveNoRuleRegardlessOfRegistry() {
        // Both exist in the registry (DIVERGENT: element_at NULL-vs-throw out-of-bounds;
        // length bytes-vs-characters — the PLAN §6.2 explicit deny examples) and neither has,
        // nor may gain, a rule without its own live proof.
        for (denied in listOf("element_at", "length")) {
            val entry = HazardRegistry.lookup("trino", "doris", denied)
            assertThat(entry).describedAs(denied).isNotNull
            assertThat(entry!!.verdict).describedAs(denied).isEqualTo(HazardVerdict.DIVERGENT)
            assertThat(DorisPushdownEvidence.ALL.map { it.registrySource })
                .describedAs("no pushdown rule may exist for '$denied'")
                .doesNotContain(denied)
        }
    }

    // --- (d) artifact pin: version + jar sha256 ---

    @Test
    fun testArtifactPinVersionAndSha256() {
        val jar = File(HazardRegistry::class.java.protectionDomain.codeSource.location.toURI())
        assertThat(jar.name)
            .describedAs("registry must load from the pinned artifact (${DorisPushdownEvidence.ARTIFACT_PIN})")
            .isEqualTo("brikk-sql-metadata-jvm-0.7.0.jar")

        val digest = MessageDigest.getInstance("SHA-256")
        val sha256 = jar.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        assertThat(sha256).isEqualTo(DorisPushdownEvidence.ARTIFACT_SHA256)
    }
}
