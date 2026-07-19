package dev.brikk.trino.doris

import io.trino.spi.Plugin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

/**
 * A real Trino server discovers plugins from the plugin directory through [ServiceLoader];
 * the programmatic `installPlugin(...)` used by the live suites bypasses that path entirely,
 * so a missing `META-INF/services/io.trino.spi.Plugin` registration is invisible to every
 * other test (this exact gap shipped unnoticed until 2026-07-19). This test pins the
 * classpath registration; `verifyPluginAssembly` pins the same entry inside the built jar.
 */
class TestDorisPluginRegistration {
    @Test
    fun `DorisPlugin is discoverable via ServiceLoader`() {
        val plugins = ServiceLoader.load(Plugin::class.java).map { it.javaClass.name }
        assertThat(plugins).contains("dev.brikk.trino.doris.DorisPlugin")
    }

    @Test
    fun `plugin exposes exactly the doris connector factory`() {
        val factories = DorisPlugin().getConnectorFactories().map { it.name }
        assertThat(factories).containsExactly("doris")
    }
}
