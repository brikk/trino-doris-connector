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

import io.airlift.slice.Slice
import io.trino.plugin.jdbc.SliceReadFunction
import io.trino.plugin.jdbc.SliceWriteFunction
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

/** Trino 483 JSON uses a parsed, sorted UTF-8 Slice. No cluster needed. */
class TestDorisJsonMapping {
    private val mapping = DorisTypeMapping(TESTING_TYPE_MANAGER)
    private val session = TestingConnectorSession.builder().build()

    @Test
    fun testJsonAndVariantReadSlicesPreservingStructureAndPrecision() {
        for (columnType in listOf("json", "variant")) {
            val columnMapping = mapping.toColumnMapping(session, columnType).orElseThrow()
            assertThat(columnMapping.type.javaType).isEqualTo(Slice::class.java)
            assertThat(columnMapping.predicatePushdownController).isSameAs(DISABLE_PUSHDOWN)
            val reader = columnMapping.readFunction as SliceReadFunction
            assertThat(reader.javaType).isEqualTo(Slice::class.java)
            for ((text, expectedText) in mapOf(
                "null" to "null", "true" to "true", "[1,null,false]" to "[1,null,false]", "\"中文\"" to "\"中文\"",
                // The 483 BigDecimal parser retains precision/scale and sorts object keys.
                "123456789012345678901234567890123456789.012300" to "123456789012345678901234567890123456789.012300",
                """{"z":1.00,"a":2,"z":3}""" to """{"a":2,"z":3}""",
            )) {
                val resultSet = resultSet(text)
                assertThat(reader.isNull(resultSet, 1)).isFalse()
                val value = reader.readSlice(resultSet, 1)
                assertThat(value.toStringUtf8()).isEqualTo(expectedText)
                val builder = columnMapping.type.createBlockBuilder(null, 1)
                columnMapping.type.writeSlice(builder, value)
                val restored = columnMapping.type.getSlice(builder.build(), 0)
                assertThat(restored.toStringUtf8()).isEqualTo(expectedText)
            }
            // SQL NULL and a non-null JSON value containing the literal null are distinct.
            assertThat(reader.isNull(resultSet(null), 1)).isTrue()
        }
    }

    @Test
    fun testMalformedJsonFailsLoud() {
        for (columnType in listOf("json", "variant")) {
            val reader = mapping.toColumnMapping(session, columnType).orElseThrow().readFunction as SliceReadFunction
            for (text in listOf(
                "", "{", "{} []", "inf", "-inf", """{"n":inf}""",
                "[".repeat(1025) + "0" + "]".repeat(1025),
            )) {
                assertThatThrownBy { reader.readSlice(resultSet(text), 1) }
                    .describedAs("$columnType must reject invalid JSON")
                    .isInstanceOf(TrinoException::class.java)
            }
        }
    }

    @Test
    fun testParameterWriterAcceptsJsonSlice() {
        for (columnType in listOf("json", "variant")) {
            val columnMapping = mapping.toColumnMapping(session, columnType).orElseThrow()
            val writer = columnMapping.writeFunction as SliceWriteFunction
            assertThat(writer.javaType).isEqualTo(Slice::class.java)
            val text = """{"z":1.00,"a":2,"z":3}"""
            val value = (columnMapping.readFunction as SliceReadFunction).readSlice(resultSet(text), 1)
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
            assertThat(bound).isEqualTo("""{"a":2,"z":3}""")
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
