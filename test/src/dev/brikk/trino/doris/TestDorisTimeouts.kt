package dev.brikk.trino.doris

import io.airlift.units.Duration
import io.trino.spi.StandardErrorCode.INVALID_SESSION_PROPERTY
import io.trino.spi.TrinoException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TestDorisTimeouts {
    @Test
    fun testConnectBoundsBeforeDriverConstruction() {
        for (text in listOf("0ms", "0.1ms", "2147483647.1ms", "30d")) {
            assertThatThrownBy { DorisConfig().setConnectTimeout(Duration.valueOf(text)) }
                .describedAs(text).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("doris.connect-timeout")
        }
        for ((text, expected) in listOf("1ms" to "1", "1.1ms" to "2", "2147483647ms" to "2147483647")) {
            val config = DorisConfig().setConnectTimeout(Duration.valueOf(text))
            assertThat(DorisClientModule.connectionProperties(config).getProperty("connectTimeout")).isEqualTo(expected)
        }
    }

    @Test
    fun testQueryBoundsAtCatalogAndSessionDecode() {
        val property = DorisSessionProperties(DorisConfig()).sessionProperties.single { it.name == "query_timeout" }
        for (text in listOf("0s", "100ms", "0.999s", "2147483647.1s", "30000d")) {
            assertThatThrownBy { DorisConfig().setQueryTimeout(Duration.valueOf(text)) }
                .describedAs(text).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("query_timeout")
            assertThatThrownBy { property.decode(text) }.describedAs(text).isInstanceOfSatisfying(TrinoException::class.java) {
                assertThat(it.errorCode).isEqualTo(INVALID_SESSION_PROPERTY.toErrorCode())
            }
        }
    }

    @Test
    fun testQueryNormalizationIsSharedByOpenAndScanAndKeepsUnsetDefault() {
        val property = DorisSessionProperties(DorisConfig()).sessionProperties.single { it.name == "query_timeout" }
        assertThat(property.defaultValue).isNull()
        assertThat(DorisClientModule.connectionProperties(DorisConfig().setQueryTimeout(null)).getProperty("sessionVariables")).isNull()
        for ((text, expected) in listOf("1s" to 1, "1.1s" to 2, "1.9s" to 2, "2147483647s" to Int.MAX_VALUE)) {
            val timeout = Duration.valueOf(text)
            val config = DorisConfig().setQueryTimeout(timeout)
            assertThat(DorisClientModule.connectionProperties(config).getProperty("sessionVariables")).isEqualTo("query_timeout=$expected")
            assertThat(DorisTimeouts.querySeconds(property.decode(text) as Duration)).isEqualTo(expected)
        }
    }
}
