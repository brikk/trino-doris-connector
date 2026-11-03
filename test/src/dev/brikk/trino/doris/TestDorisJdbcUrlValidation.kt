package dev.brikk.trino.doris

import com.mysql.cj.conf.ConnectionPropertiesTransform
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

class TestDorisJdbcUrlValidation {
    @Test
    fun testSupportedJdbcFamiliesAndNonResolvingSrvValidation() {
        for (url in listOf(
            "jdbc:mysql://fe:9030", "JDBC:MYSQL://fe:9030/", "jdbc:mysql://fe1:9030,fe2:9030/",
            "jdbc:mysql:loadbalance://fe1:9030,fe2:9030/", "jdbc:mysql:replication://fe1:9030,fe2:9030/",
            "jdbc:mysql+srv://does-not-exist.invalid/", "jdbc:mysql+srv:loadbalance://does-not-exist.invalid/",
            "jdbc:mysql+srv:replication://source.invalid,replica.invalid/", "jdbc:mysql://does-not-exist.invalid/?dnsSrv=true",
            "jdbc:mysql://[::1]:9030/", "jdbc:mysql://address=(host=fe)(port=9030)/",
        )) {
            val config = config(url)
            assertThat(config.isUrlValid()).describedAs(url).isTrue()
            assertThat(config.isUrlWithoutDatabase()).describedAs(url).isTrue()
        }
    }

    @Test
    fun testUnsupportedProtocolsAndMalformedEncodingFailClosed() {
        for (url in listOf(null, "", "mysqlx://fe:9030", "mysqlx+srv://fe", "jdbc:mysqlx://fe:9030",
            "jdbc:postgresql://fe:5432", "jdbc:mysql://fe/%zz", "jdbc:mysql://fe/?%zz=x", "jdbc:mysql://fe:not_a_port/")) {
            assertThat(config(url).isUrlValid()).describedAs(url).isFalse()
            assertThat(config(url).isUrlWithoutDatabase()).describedAs(url).isFalse()
        }
    }

    @Test
    fun testPathGlobalAndPerHostDatabaseSettingsAreAllRejected() {
        for (suffix in listOf("hidden_db", "%68idden_db", "?DBNAME=hidden_db", "?dbname=hidden_db", "?dBnAmE=%68idden_db",
            "?%64bname=hidden_db", "?DBNAME=hidden_db&dbname=", "?dbname=%20")) {
            assertThat(config("jdbc:mysql://fe:9030/$suffix").isUrlWithoutDatabase()).describedAs(suffix).isFalse()
        }
        for (url in listOf(
            "jdbc:mysql://address=(host=fe)(port=9030)(DBNAME=hidden_db)/",
            "jdbc:mysql://address=(host=fe)(port=9030)(%64bname=hidden_db)/",
            "jdbc:mysql:loadbalance://address=(host=fe1)(dbname=hidden_db),fe2:9030/",
            "jdbc:mysql:replication://fe1:9030,address=(host=fe2)(dBnAmE=hidden_db)/",
            "jdbc:mysql+srv://address=(host=service.invalid)(dbname=hidden_db)/",
        )) assertThat(config(url).isUrlWithoutDatabase()).describedAs(url).isFalse()
    }

    @Test
    fun testEmptyDatabaseAndIncidentalTextAreNotFalsePositives() {
        for (url in listOf(
            "jdbc:mysql://fe:9030/?DBNAME=", "jdbc:mysql://address=(host=fe)(dbname=)/",
            "jdbc:mysql://DBNAME%3Dhidden:p%2Fword@fe:9030/?connectionAttributes=note:DBNAME%3Dhidden",
            "jdbc:mysql://fe:9030/?propertiesTransform=&useConfigs=",
        )) {
            assertThat(config(url).isUrlValid()).describedAs(url).isTrue()
            assertThat(config(url).isUrlWithoutDatabase()).describedAs(url).isTrue()
        }
    }

    @Test
    fun testPropertyIndirectionIsRejectedWithoutInstantiatingUserCode() {
        val before = transformCalls.get()
        for (property in listOf("propertiesTransform", "%70ropertiesTransform", "PROPERTIESTRANSFORM", "useConfigs")) {
            val value = if (property.equals("useConfigs", ignoreCase = true)) "does-not-exist" else ForbiddenTransform::class.java.name
            for (url in listOf("jdbc:mysql://fe/?$property=$value", "jdbc:mysql://address=(host=fe)($property=$value)/")) {
                assertThat(config(url).isUrlValid()).describedAs(url).isFalse()
                assertThat(config(url).isUrlWithoutDatabase()).describedAs(url).isFalse()
            }
        }
        assertThat(transformCalls.get()).isEqualTo(before)
    }

    @Test
    fun testMutableConfigDoesNotReuseAStaleValidation() {
        val config = config("jdbc:mysql://fe:9030/")
        assertThat(config.isUrlWithoutDatabase()).isTrue()
        config.setConnectionUrl("jdbc:mysql://fe:9030/?DBNAME=hidden")
        assertThat(config.isUrlWithoutDatabase()).isFalse()
        config.setConnectionUrl("jdbc:mysql://fe:9030/")
        assertThat(config.isUrlWithoutDatabase()).isTrue()
    }

    class ForbiddenTransform : ConnectionPropertiesTransform {
        init { transformCalls.incrementAndGet() }
        override fun transformProperties(properties: Properties): Properties {
            transformCalls.incrementAndGet()
            return properties.apply { setProperty("dbname", "hidden") }
        }
    }

    companion object {
        private val transformCalls = AtomicInteger()
        private fun config(url: String?): DorisJdbcConfig = DorisJdbcConfig().also { it.setConnectionUrl(url) }
    }
}
