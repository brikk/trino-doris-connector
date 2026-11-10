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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TestDorisTestProcess {
    @Test
    fun testGrepMatchesAndNoMatch() {
        assertThat(runTestProcess(listOf("sh", "-c", "printf 'query cancelled\\n'")).grepMatches())
            .containsExactly("query cancelled")
        assertThat(runTestProcess(listOf("sh", "-c", "exit 1")).grepMatches()).isEmpty()
    }

    @Test
    fun testCommandFailureIsNotEvidence() {
        for (code in listOf(1, 2, 125)) {
            val result = runTestProcess(listOf("sh", "-c", "printf 'docker exec failed' >&2; exit $code"))
            assertThatThrownBy { result.grepMatches() }.hasMessageContaining("docker exec failed")
        }
    }

    @Test
    fun testTimeoutIncludesOutputCollection() {
        assertThatThrownBy { runTestProcess(listOf("sh", "-c", "printf partial; exec sleep 30"), 100) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("timed out")
    }

    @Test
    fun testOutputCannotBlockProcessCompletion() {
        val result = runTestProcess(listOf("sh", "-c", "head -c 1048576 /dev/zero; head -c 1048576 /dev/zero >&2"))
        assertThat(result.exitCode).isZero()
        assertThat(result.stdout).hasSize(1048576)
        assertThat(result.stderr).hasSize(1048576)
    }
}
