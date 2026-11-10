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

import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal data class DorisTestProcess(val exitCode: Int, val stdout: String, val stderr: String) {
    fun grepMatches(): List<String> = when (exitCode) {
        0 -> stdout.lines().filter { it.isNotBlank() }
        1 -> {
            check(stdout.isEmpty() && stderr.isEmpty()) { "grep failed: $stderr$stdout" }
            emptyList()
        }
        else -> error("Log lookup exited with $exitCode: $stderr")
    }
}

internal fun runTestProcess(command: List<String>, timeoutMillis: Long = 30_000): DorisTestProcess {
    val stdout = Files.createTempFile("doris-test-stdout", ".log")
    val stderr = Files.createTempFile("doris-test-stderr", ".log")
    try {
        val process = ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        try {
            check(process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { "Process timed out after ${timeoutMillis}ms: $command" }
            return DorisTestProcess(process.exitValue(), Files.readString(stdout), Files.readString(stderr))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    } finally {
        Files.deleteIfExists(stdout)
        Files.deleteIfExists(stderr)
    }
}
