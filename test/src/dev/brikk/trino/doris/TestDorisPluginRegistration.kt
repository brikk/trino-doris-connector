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

import io.trino.spi.Plugin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

// Live suites install the plugin programmatically and cannot catch missing ServiceLoader registration.
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
