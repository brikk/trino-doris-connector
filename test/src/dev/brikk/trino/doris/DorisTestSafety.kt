package dev.brikk.trino.doris

import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Test-only authorization, deliberately not a production connector property. */
internal object DorisTestSafety {
    private val gate = DorisTestSafetyGate(System.getenv(), DockerDorisTestInspector())

    fun connectSingle(url: String, properties: Properties = Properties()): Connection =
        openSingle { DriverManager.getConnection(url, properties) }

    fun openSingle(open: () -> Connection): Connection = gate.open(DorisTestTarget.SINGLE, open)

    fun connectOverlay(url: String, properties: Properties): Connection =
        gate.open(DorisTestTarget.OVERLAY) { DriverManager.getConnection(url, properties) }

    fun requireSingle() { gate.authorize(DorisTestTarget.SINGLE) }

    fun overlayRequested(): Boolean = gate.overlayRequested()

    fun requireOverlay() { gate.authorize(DorisTestTarget.OVERLAY) }

    fun actionContainerId(container: String, starting: Boolean): String = gate.actionContainerId(container, starting)
}

internal data class DorisTestNode(val name: String, val service: String, val ip: String, val port: Int? = null)

internal enum class DorisTestTarget(val project: String, val label: String, val network: String, val nodes: List<DorisTestNode>) {
    SINGLE("trino-doris-dev", "single-fe", "doris-net", listOf(
        DorisTestNode("trino-doris-fe", "doris-fe", "172.30.81.10", 9130),
        DorisTestNode("trino-doris-be", "doris-be", "172.30.81.20"),
    )),
    OVERLAY("trino-doris-mfe", "multi-fe", "mfe-net", listOf(
        DorisTestNode("trino-doris-mfe-fe1", "doris-fe1", "172.30.82.10", 9131),
        DorisTestNode("trino-doris-mfe-fe2", "doris-fe2", "172.30.82.11", 9132),
        DorisTestNode("trino-doris-mfe-fe3", "doris-fe3", "172.30.82.12", 9133),
        DorisTestNode("trino-doris-mfe-be", "doris-be", "172.30.82.20"),
    )),
}

internal data class DorisTestContainer(
    val id: String, val running: Boolean, val project: String, val service: String,
    val label: String, val image: String, val ip: String, val ports: Set<Int>,
)

internal data class DorisTestNativeIdentity(
    val clusterId: Long, val hosts: Set<String>, val backendHosts: Set<String>, val versions: Set<String>,
)

internal interface DorisTestInspector {
    fun inspect(target: DorisTestTarget, node: DorisTestNode): DorisTestContainer
    fun nativeIdentity(containerId: String): DorisTestNativeIdentity
    fun inspectAll(target: DorisTestTarget): Map<String, DorisTestContainer> =
        target.nodes.associate { it.name to inspect(target, it) }
}

/**
 * Cache the original IDs/native proof, not permission or current ownership. Every
 * connection rechecks Docker ownership/IDs and must match its native FE/BE identity
 * before callers receive it. Lifecycle actions also use the original immutable ID.
 */
internal class DorisTestSafetyGate(private val environment: Map<String, String>, private val inspector: DorisTestInspector) {
    internal data class Proof(val containers: Map<String, DorisTestContainer>, val native: DorisTestNativeIdentity)
    private val proofs = mutableMapOf<DorisTestTarget, Proof>()

    fun overlayRequested(): Boolean = environment[FAILOVER] == "1"

    @Synchronized
    fun authorize(target: DorisTestTarget): Proof {
        check(environment[MUTATION] == "1") {
            "Live tests may DROP/rebuild fixture schemas. Set $MUTATION=1 explicitly for the disposable Compose cluster."
        }
        if (target == DorisTestTarget.OVERLAY) {
            check(overlayRequested()) { "Overlay tests may stop containers; set $FAILOVER=1 explicitly too." }
        }
        val previous = proofs[target]
        val containers = inspector.inspectAll(target)
        check(containers.keys == target.nodes.map { it.name }.toSet()) { "Unexpected disposable container roster" }
        for (node in target.nodes) {
            // An explicitly authorized overlay can have a stopped follower during failover.
            validateContainer(target, node, containers.getValue(node.name), running = previous == null || target == DorisTestTarget.SINGLE)
        }
        if (previous != null) {
            check(containers.all { (name, current) -> current.id == previous.containers.getValue(name).id }) {
                "Refusing access to a replaced container in the disposable ${target.label} cluster"
            }
            return previous
        }
        val native = inspector.nativeIdentity(containers.getValue(target.nodes.first().name).id)
        validateNative(target, native)
        return Proof(containers, native).also { proofs[target] = it }
    }

    fun open(target: DorisTestTarget, open: () -> Connection): Connection {
        val proof = authorize(target) // no socket or SQL before explicit authorization
        val connection = open()
        try {
            val actual = nativeIdentity(connection)
            check(actual == proof.native) { "JDBC target is not the authorized disposable ${target.label} cluster: $actual" }
            return connection
        } catch (failure: Exception) {
            runCatching { connection.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun nativeIdentity(connection: Connection): DorisTestNativeIdentity {
        val clusterIds = mutableSetOf<Long>()
        val hosts = mutableSetOf<String>()
        val backends = mutableSetOf<String>()
        val versions = mutableSetOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SHOW FRONTENDS").use { rows ->
                while (rows.next()) {
                    clusterIds.add(rows.getLong("ClusterId"))
                    hosts.add(rows.getString("Host"))
                    versions.add(rows.getString("Version"))
                }
            }
            statement.executeQuery("SHOW BACKENDS").use { rows ->
                while (rows.next()) {
                    backends.add(rows.getString("Host"))
                    versions.add(rows.getString("Version"))
                }
            }
        }
        check(clusterIds.size == 1) { "JDBC target has no unique native ClusterId" }
        return DorisTestNativeIdentity(clusterIds.single(), hosts, backends, versions)
    }

    fun actionContainerId(name: String, starting: Boolean): String {
        val target = DorisTestTarget.OVERLAY
        val proof = authorize(target)
        val node = target.nodes.singleOrNull { it.name == name && it.port != null }
            ?: error("Not an authorized overlay frontend: $name")
        val current = inspector.inspect(target, node)
        validateContainer(target, node, current, running = !starting)
        check(current.id == proof.containers.getValue(name).id) { "Refusing lifecycle action on replaced container $name" }
        return current.id // never send a mutable name to docker stop/start
    }

    private fun validateContainer(target: DorisTestTarget, node: DorisTestNode, actual: DorisTestContainer, running: Boolean) {
        check(actual.id.matches(Regex("[0-9a-f]{64}")) && (!running || actual.running)) { "Disposable container ${node.name} is not running/identified" }
        check(actual.project == target.project && actual.service == node.service && actual.label == target.label) {
            "Refusing non-disposable or foreign Compose container ${node.name}; use the checked-in compose/up.sh"
        }
        val image = if (node.port == null) "be" else "fe"
        check(actual.image.startsWith("apache/doris:$image-4.1.5@sha256:") && actual.ip == node.ip) {
            "Unexpected image/network identity for ${node.name}"
        }
        check(node.port == null || actual.ports == setOf(node.port)) { "Unexpected host port mapping for ${node.name}" }
    }

    private fun validateNative(target: DorisTestTarget, native: DorisTestNativeIdentity) {
        check(native.clusterId > 0 && native.hosts == target.nodes.filter { it.port != null }.map { it.ip }.toSet() &&
            native.backendHosts == target.nodes.filter { it.port == null }.map { it.ip }.toSet() &&
            native.versions == setOf(DorisTestCluster.GOLDEN_NATIVE_VERSION)
        ) { "Unexpected native disposable-cluster identity: $native" }
    }

    companion object {
        const val MUTATION = "DORIS_TEST_ALLOW_MUTATION"
        const val FAILOVER = "DORIS_TEST_ALLOW_FAILOVER"
    }
}

private class DockerDorisTestInspector : DorisTestInspector {
    override fun inspect(target: DorisTestTarget, node: DorisTestNode): DorisTestContainer = inspectNodes(target, listOf(node)).getValue(node.name)

    override fun inspectAll(target: DorisTestTarget): Map<String, DorisTestContainer> = inspectNodes(target, target.nodes)

    private fun inspectNodes(target: DorisTestTarget, nodes: List<DorisTestNode>): Map<String, DorisTestContainer> {
        val format = "{{.Id}}|{{.State.Running}}|{{index .Config.Labels \"com.docker.compose.project\"}}|" +
            "{{index .Config.Labels \"com.docker.compose.service\"}}|{{index .Config.Labels \"dev.brikk.trino-doris.test-cluster\"}}|" +
            "{{.Config.Image}}|{{with index .NetworkSettings.Networks \"${target.project}_${target.network}\"}}" +
            "{{.IPAMConfig.IPv4Address}}{{end}}|{{with index .HostConfig.PortBindings \"9030/tcp\"}}{{range .}}{{.HostPort}},{{end}}{{end}}"
        val lines = docker("inspect", "--format", format, *nodes.map { it.name }.toTypedArray()).trim().lines()
        check(lines.size == nodes.size) { "Invalid Docker disposable-container roster response" }
        return nodes.zip(lines).associate { (node, line) ->
            val fields = line.split('|')
            check(fields.size == 8) { "Invalid Docker identity response for ${node.name}" }
            node.name to DorisTestContainer(fields[0], fields[1] == "true", fields[2], fields[3], fields[4], fields[5], fields[6],
                fields[7].split(',').filter { it.isNotBlank() }.map { it.toInt() }.toSet())
        }
    }

    override fun nativeIdentity(containerId: String): DorisTestNativeIdentity {
        val frontends = nativeRows(containerId, "SHOW FRONTENDS")
        val backends = nativeRows(containerId, "SHOW BACKENDS")
        val ids = frontends.map { it.getValue("ClusterId").toLong() }.toSet()
        check(ids.size == 1) { "Docker FE has no unique native ClusterId" }
        return DorisTestNativeIdentity(ids.single(), frontends.map { it.getValue("Host") }.toSet(),
            backends.map { it.getValue("Host") }.toSet(), (frontends + backends).map { it.getValue("Version") }.toSet())
    }

    private fun nativeRows(containerId: String, sql: String): List<Map<String, String>> {
        val lines = docker("exec", containerId, "mysql", "-h127.0.0.1", "-P9030", "-uroot", "--batch", "-e", sql).trim().lines()
        val columns = lines.first().split('\t')
        return lines.drop(1).map { line -> columns.zip(line.split('\t')).toMap() }
    }

    private fun docker(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("docker", *arguments)).redirectErrorStream(true).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Disposable-cluster Docker identity check timed out")
        }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { "Disposable-cluster Docker identity check failed: ${output.take(2000)}" }
        return output
    }
}
