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
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

class TestDorisTestSafety {
    @Test
    fun testNoOptInMeansNoDockerConnectionOrSqlEvenWithMatchingEndpoints() {
        val fixture = Fixture(DorisTestTarget.SINGLE)
        fixture.environment.clear()
        assertThatThrownBy { fixture.open() }.hasMessageContaining(DorisTestSafetyGate.MUTATION)
        assertThat(fixture.events).isEmpty()
        fixture.environment[DorisTestSafetyGate.MUTATION] = "true" // exact explicit 1, not generic truthiness
        assertThatThrownBy { fixture.open() }.hasMessageContaining(DorisTestSafetyGate.MUTATION)
        assertThat(fixture.events).isEmpty()
    }

    @Test
    fun testWrongDisposableIdentityFailsBeforeAnyConnection() {
        val changes: List<(DorisTestContainer) -> DorisTestContainer> = listOf(
            { it.copy(label = "") }, { it.copy(project = "production") }, { it.copy(service = "foreign") },
            { it.copy(ip = "192.0.2.1") }, { it.copy(ports = setOf(9030)) },
            { it.copy(image = "apache/doris:fe-4.2.0") }, { it.copy(running = false) },
        )
        for (change in changes) {
            val fixture = Fixture(DorisTestTarget.SINGLE)
            val node = fixture.target.nodes.first().name
            fixture.containers[node] = change(fixture.containers.getValue(node))
            assertThatThrownBy { fixture.open() }.isInstanceOf(IllegalStateException::class.java)
            assertThat(fixture.events).doesNotContain("connect", "SHOW FRONTENDS", "close")
        }
    }

    @Test
    fun testSameGoldenVersionButForeignNativeClusterNeverReachesFixtureCode() {
        for (foreign in listOf(
            identity(DorisTestTarget.SINGLE).copy(clusterId = 999),
            identity(DorisTestTarget.SINGLE).copy(hosts = setOf("192.0.2.1")),
            identity(DorisTestTarget.SINGLE).copy(backendHosts = setOf("192.0.2.2")),
            identity(DorisTestTarget.SINGLE).copy(versions = setOf("doris-4.2.0")),
        )) {
            val fixture = Fixture(DorisTestTarget.SINGLE)
            fixture.jdbcIdentity = foreign
            assertThatThrownBy { fixture.open() }.hasMessageContaining("not the authorized disposable")
            assertThat(fixture.events.takeLast(4)).containsExactly("connect", "SHOW FRONTENDS", "SHOW BACKENDS", "close")
        }
    }

    @Test
    fun testAuthorizedConnectionChecksNativeIdentityEveryTimeAndRechecksPermission() {
        val fixture = Fixture(DorisTestTarget.SINGLE)
        fixture.open().close()
        fixture.open().close()
        assertThat(fixture.events.count { it == "SHOW FRONTENDS" }).isEqualTo(2)
        assertThat(fixture.events.count { it.startsWith("inspect ") }).isEqualTo(4) // FE + BE rechecked on each open
        fixture.environment.clear()
        val before = fixture.events.toList()
        assertThatThrownBy { fixture.open() }.hasMessageContaining(DorisTestSafetyGate.MUTATION)
        assertThat(fixture.events).isEqualTo(before)
    }

    @Test
    fun testCachedProofCannotAuthorizeAReplacedOrRelabeledConnectionTarget() {
        for (replace in listOf(false, true)) {
            val fixture = Fixture(DorisTestTarget.SINGLE)
            fixture.open().close()
            val name = fixture.target.nodes.first().name
            val original = fixture.containers.getValue(name)
            fixture.containers[name] = if (replace) original.copy(id = "f".repeat(64)) else original.copy(label = "production")
            assertThatThrownBy { fixture.open() }.isInstanceOf(IllegalStateException::class.java)
            assertThat(fixture.events.count { it == "connect" }).isEqualTo(1)
        }
    }

    @Test
    fun testReachableOverlayNeverEnablesFailoverWithoutSeparateOptIn() {
        val fixture = Fixture(DorisTestTarget.OVERLAY)
        fixture.environment.remove(DorisTestSafetyGate.FAILOVER)
        assertThat(fixture.gate.overlayRequested()).isFalse()
        assertThatThrownBy { fixture.gate.authorize(fixture.target) }.hasMessageContaining(DorisTestSafetyGate.FAILOVER)
        assertThat(fixture.events).isEmpty()
        fixture.environment[DorisTestSafetyGate.FAILOVER] = "1"
        fixture.environment.remove(DorisTestSafetyGate.MUTATION)
        assertThatThrownBy { fixture.gate.authorize(fixture.target) }.hasMessageContaining(DorisTestSafetyGate.MUTATION)
        assertThat(fixture.events).isEmpty()
    }

    @Test
    fun testLifecycleUsesOriginalImmutableIdAndRefusesReplacedOrForeignContainers() {
        val fixture = Fixture(DorisTestTarget.OVERLAY)
        fixture.gate.authorize(fixture.target)
        val node = fixture.target.nodes[1]
        val original = fixture.containers.getValue(node.name)
        assertThat(fixture.gate.actionContainerId(node.name, starting = false)).isEqualTo(original.id)
        fixture.containers[node.name] = original.copy(running = false)
        assertThat(fixture.gate.actionContainerId(node.name, starting = true)).isEqualTo(original.id)
        fixture.containers[node.name] = original.copy(id = "f".repeat(64))
        assertThatThrownBy { fixture.gate.actionContainerId(node.name, starting = false) }.hasMessageContaining("replaced container")
        fixture.containers[node.name] = original
        assertThatThrownBy { fixture.gate.actionContainerId("production-fe", starting = false) }.hasMessageContaining("Not an authorized")
        assertThatThrownBy { fixture.gate.actionContainerId(fixture.target.nodes.last().name, starting = false) }
            .hasMessageContaining("Not an authorized")
        fixture.containers[node.name] = original.copy(label = "production")
        assertThatThrownBy { fixture.gate.actionContainerId(node.name, starting = false) }.hasMessageContaining("non-disposable")
    }

    private class Fixture(val target: DorisTestTarget) {
        val environment = mutableMapOf(DorisTestSafetyGate.MUTATION to "1", DorisTestSafetyGate.FAILOVER to "1")
        val events = mutableListOf<String>()
        var jdbcIdentity = identity(target)
        val containers = target.nodes.mapIndexed { index, node ->
            node.name to DorisTestContainer((index + 1).toString().padStart(64, '0'), true, target.project, node.service,
                target.label, "apache/doris:${if (node.port == null) "be" else "fe"}-4.1.5@sha256:${"a".repeat(64)}",
                node.ip, setOfNotNull(node.port))
        }.toMap().toMutableMap()
        val gate = DorisTestSafetyGate(environment, object : DorisTestInspector {
            override fun inspect(target: DorisTestTarget, node: DorisTestNode): DorisTestContainer {
                events.add("inspect ${node.name}")
                return containers.getValue(node.name)
            }
            override fun nativeIdentity(containerId: String): DorisTestNativeIdentity {
                events.add("native $containerId")
                return identity(target)
            }
        })

        fun open(): Connection = gate.open(target) {
            events.add("connect")
            jdbcProxy(Connection::class.java) { method, _ ->
                when (method) {
                    "createStatement" -> statement()
                    "close" -> { events.add("close"); null }
                    else -> error("Unauthorized/unexpected JDBC call $method")
                }
            }
        }

        private fun statement(): Statement = jdbcProxy(Statement::class.java) { method, arguments ->
            when (method) {
                "executeQuery" -> {
                    check(arguments[0] in listOf("SHOW FRONTENDS", "SHOW BACKENDS")) // no mutation before validation
                    events.add(arguments[0] as String)
                    result(backend = arguments[0] == "SHOW BACKENDS")
                }
                "close" -> null
                else -> error("Unauthorized/unexpected statement call $method")
            }
        }

        private fun result(backend: Boolean): ResultSet {
            val hosts = (if (backend) jdbcIdentity.backendHosts else jdbcIdentity.hosts).toList()
            var row = -1
            return jdbcProxy(ResultSet::class.java) { method, arguments ->
                when (method) {
                    "next" -> ++row < hosts.size
                    "getLong" -> jdbcIdentity.clusterId
                    "getString" -> when (arguments[0]) {
                        "Host" -> hosts[row]
                        "Version" -> jdbcIdentity.versions.single()
                        else -> error("Unexpected column")
                    }
                    "close" -> null
                    else -> error("Unexpected result call $method")
                }
            }
        }
    }

    companion object {
        private fun identity(target: DorisTestTarget) = DorisTestNativeIdentity(123,
            target.nodes.filter { it.port != null }.map { it.ip }.toSet(),
            target.nodes.filter { it.port == null }.map { it.ip }.toSet(), setOf(DorisTestCluster.GOLDEN_NATIVE_VERSION))

    }
}
