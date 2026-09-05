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

import io.trino.plugin.base.mapping.DefaultIdentifierMapping
import io.trino.plugin.jdbc.BaseJdbcConfig
import io.trino.plugin.jdbc.ConnectionFactory
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcSortItem
import io.trino.plugin.jdbc.JdbcTableHandle
import io.trino.plugin.jdbc.JdbcTypeHandle
import io.trino.plugin.jdbc.RemoteTableName
import io.trino.plugin.jdbc.logging.RemoteQueryModifier
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.connector.SchemaTableName
import io.trino.spi.connector.SortOrder.ASC_NULLS_LAST
import io.trino.spi.type.IntegerType.INTEGER
import io.trino.spi.type.VarcharType.VARCHAR
import io.trino.spi.type.VarcharType.createVarcharType
import io.trino.testing.TestingConnectorSession
import io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Types
import java.util.Optional

class TestDorisStringKeyPushdown {
    private val config = DorisConfig()
    private val client = DorisClient(
        BaseJdbcConfig(), config,
        ConnectionFactory { throw AssertionError("Key admission must not open a connection") },
        DorisQueryBuilder(RemoteQueryModifier.NONE), TESTING_TYPE_MANAGER,
        DefaultIdentifierMapping(), RemoteQueryModifier.NONE,
        DorisCancelConnectionFactory { _, _ -> throw AssertionError("Key admission must not cancel queries") },
    )
    private val table = JdbcTableHandle(
        SchemaTableName("test", "keys"),
        RemoteTableName(Optional.of("test"), Optional.empty(), "keys"),
        Optional.empty(),
    )
    private val integer = JdbcColumnHandle("id", DorisTypeMapping.toTypeHandle("int"), INTEGER)

    @Test
    fun testNativeAndSyntheticStringKeysRemainEligible() {
        for (mode in DorisStringPushdownMode.entries) {
            val session = session(mode)
            for (remoteType in listOf("VARCHAR(50)", "string", "text")) {
                for (name in listOf("value", "_pfgnrtd_0")) {
                    val type = if (remoteType.startsWith("VARCHAR")) createVarcharType(50) else VARCHAR
                    val column = JdbcColumnHandle(name, DorisTypeMapping.toTypeHandle(remoteType), type)
                    assertThat(client.supportsTopN(session, table, listOf(JdbcSortItem(column, ASC_NULLS_LAST))))
                        .describedAs("$mode $remoteType $name").isEqualTo(mode.allowsFullStringPushdown)
                    assertThat(client.supportsAggregationPushdown(session, table, emptyList(), emptyMap(), listOf(listOf(column))))
                        .describedAs("$mode $remoteType $name").isTrue()
                }
            }
        }
    }

    @Test
    fun testTextMappedAndUnknownStringKeysAreRejected() {
        for (remoteType in listOf(
            "array<varchar(50)>", "array<array<string>>", "map<string,int>", "struct<a:int,b:string>",
            "decimal(76,10)", "decimalv3(50,2)", "char(50)", "json", "unknown", null,
        )) {
            val typeHandle = remoteType?.let(DorisTypeMapping::toTypeHandle) ?: JdbcTypeHandle(
                Types.VARCHAR, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            )
            val column = JdbcColumnHandle("text_value", typeHandle, VARCHAR)
            for (mode in DorisStringPushdownMode.entries) {
                val session = session(mode)
                assertThat(client.supportsTopN(session, table, listOf(JdbcSortItem(column, ASC_NULLS_LAST))))
                    .describedAs("$mode $remoteType").isFalse()
                assertThat(client.supportsAggregationPushdown(session, table, emptyList(), emptyMap(), listOf(listOf(column))))
                    .describedAs("$mode $remoteType").isFalse()
            }
        }
    }

    @Test
    fun testOneTextMappedKeyKeepsTheWholeOperationLocal() {
        val session = session(DorisStringPushdownMode.BINARY)
        val text = JdbcColumnHandle("text_value", DorisTypeMapping.toTypeHandle("array<string>"), VARCHAR)
        for (columns in listOf(listOf(integer, text), listOf(text, integer))) {
            assertThat(client.supportsTopN(session, table, columns.map { JdbcSortItem(it, ASC_NULLS_LAST) })).isFalse()
            assertThat(client.supportsAggregationPushdown(session, table, emptyList(), emptyMap(), listOf(columns))).isFalse()
        }
        assertThat(client.supportsAggregationPushdown(session, table, emptyList(), emptyMap(), listOf(listOf(integer), listOf(text))))
            .isFalse()
        assertThat(client.supportsTopN(session, table, listOf(JdbcSortItem(integer, ASC_NULLS_LAST)))).isTrue()
        assertThat(client.supportsAggregationPushdown(session, table, emptyList(), emptyMap(), listOf(listOf(integer)))).isTrue()
        assertThat(client.supportsAggregationPushdown(session, table, emptyList(), emptyMap(), listOf(emptyList()))).isTrue()
    }

    private fun session(mode: DorisStringPushdownMode): ConnectorSession = TestingConnectorSession.builder()
        .setPropertyMetadata(DorisSessionProperties(config).sessionProperties)
        .setPropertyValues(mapOf("string_pushdown_mode" to mode.name))
        .build()
}
