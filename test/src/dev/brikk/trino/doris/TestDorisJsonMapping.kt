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

import io.trino.json.Json
import io.trino.json.JsonItems
import io.trino.plugin.jdbc.ObjectReadFunction
import io.trino.plugin.jdbc.ObjectWriteFunction
import io.trino.plugin.jdbc.PredicatePushdownController.DISABLE_PUSHDOWN
import io.trino.spi.TrinoException
import io.trino.testing.TestingConnectorSession
import io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.PreparedStatement
import java.sql.ResultSet

/** Trino 484 JSON is a native Json object, not a UTF-8 Slice. No cluster needed. */
class TestDorisJsonMapping {
    private val mapping = DorisTypeMapping(TESTING_TYPE_MANAGER)
    private val session = TestingConnectorSession.builder().build()

    @Test
    fun testJsonAndVariantReadNativeValuesPreservingStructureAndPrecision() {
        for (columnType in listOf("json", "variant")) {
            val columnMapping = mapping.toColumnMapping(session, columnType).orElseThrow()
            assertThat(columnMapping.type.javaType).isEqualTo(Json::class.java)
            assertThat(columnMapping.predicatePushdownController).isSameAs(DISABLE_PUSHDOWN)
            val reader = columnMapping.readFunction as ObjectReadFunction
            assertThat(reader.javaType).isEqualTo(Json::class.java)
            for ((text, expectedText) in mapOf(
                "null" to "null", "true" to "true", "[1,null,false]" to "[1,null,false]", "\"中文\"" to "\"中文\"",
                // NUMBER (>38 digits) retains the exact value but normalizes trailing zeroes.
                "123456789012345678901234567890123456789.012300" to "123456789012345678901234567890123456789.0123",
                """{"z":1.00,"a":2,"z":3}""" to """{"z":1.00,"a":2,"z":3}""",
            )) {
                val resultSet = resultSet(text)
                assertThat(reader.isNull(resultSet, 1)).isFalse()
                val value = reader.readObject(resultSet, 1) as Json
                assertThat(JsonItems.toText(value).toStringUtf8()).isEqualTo(expectedText)
                val builder = columnMapping.type.createBlockBuilder(null, 1)
                columnMapping.type.writeObject(builder, value)
                val restored = columnMapping.type.getObject(builder.build(), 0) as Json
                assertThat(JsonItems.toText(restored).toStringUtf8()).isEqualTo(expectedText)
            }
            // SQL NULL and a non-null JSON value containing the literal null are distinct.
            assertThat(reader.isNull(resultSet(null), 1)).isTrue()
        }
    }

    @Test
    fun testMalformedAndNewlyInvalidJsonFailLoud() {
        for (columnType in listOf("json", "variant")) {
            val reader = mapping.toColumnMapping(session, columnType).orElseThrow().readFunction as ObjectReadFunction
            for (text in listOf(
                "", "{", "{} []", "1e9999", "inf", "-inf", """{"n":inf}""",
                "\"\\uD800\"", "[".repeat(1025) + "0" + "]".repeat(1025),
            )) {
                assertThatThrownBy { reader.readObject(resultSet(text), 1) }
                    .describedAs("$columnType must reject invalid JSON")
                    .isInstanceOf(TrinoException::class.java)
            }
        }
    }

    @Test
    fun testParameterWriterAcceptsNativeJson() {
        for (columnType in listOf("json", "variant")) {
            val columnMapping = mapping.toColumnMapping(session, columnType).orElseThrow()
            val writer = columnMapping.writeFunction as ObjectWriteFunction
            assertThat(writer.javaType).isEqualTo(Json::class.java)
            val text = """{"z":1.00,"a":2,"z":3}"""
            val value = (columnMapping.readFunction as ObjectReadFunction).readObject(resultSet(text), 1)
            var bound: String? = null
            val statement = Proxy.newProxyInstance(
                PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "setString" -> bound = arguments[1] as String
                    "setNull" -> bound = null
                    else -> throw AssertionError("Unexpected JDBC call: ${method.name}")
                }
                null
            } as PreparedStatement
            writer.set(statement, 1, value)
            assertThat(bound).isEqualTo(text)
            writer.setNull(statement, 1)
            assertThat(bound).isNull()
        }
    }

    private fun resultSet(text: String?): ResultSet = Proxy.newProxyInstance(
        ResultSet::class.java.classLoader, arrayOf(ResultSet::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getString", "getObject" -> text
            "wasNull" -> text == null
            else -> throw AssertionError("Unexpected JDBC call: ${method.name}")
        }
    } as ResultSet
}
