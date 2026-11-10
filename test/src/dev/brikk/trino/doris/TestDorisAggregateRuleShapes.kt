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
import io.trino.plugin.jdbc.logging.RemoteQueryModifier
import io.trino.spi.connector.AggregateFunction
import io.trino.spi.connector.ColumnHandle
import io.trino.spi.expression.Variable
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.IntegerType.INTEGER
import io.trino.spi.type.Type
import io.trino.testing.TestingConnectorSession
import io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class TestDorisAggregateRuleShapes {
    private val config = DorisConfig()
    private val client = DorisClient(
        BaseJdbcConfig(), config,
        ConnectionFactory { throw AssertionError("Aggregate admission must not open a connection") },
        DorisQueryBuilder(RemoteQueryModifier.NONE), TESTING_TYPE_MANAGER,
        DefaultIdentifierMapping(), RemoteQueryModifier.NONE,
        DorisCancelConnectionFactory { _, _ -> throw AssertionError("Aggregate admission must not cancel queries") },
    )
    private val session = TestingConnectorSession.builder()
        .setPropertyMetadata(DorisSessionProperties(config).sessionProperties)
        .setPropertyValues(mapOf("approximate_pushdown" to true))
        .build()
    private val value = JdbcColumnHandle.builder()
        .setColumnName("value")
        .setJdbcTypeHandle(DorisTypeMapping.toTypeHandle("int"))
        .setColumnType(INTEGER)
        .setNullable(false)
        .build()
    private val key = JdbcColumnHandle("id", DorisTypeMapping.toTypeHandle("int"), INTEGER)
    private val assignments = mapOf<String, ColumnHandle>("value" to value, "id" to key)

    @Test
    fun testExpectedShapesPush() {
        for (aggregate in listOf(
            aggregate("min", INTEGER, Variable("value", INTEGER)),
            aggregate("count", BIGINT, Variable("value", INTEGER), distinct = true),
            aggregate("min_by", INTEGER, Variable("value", INTEGER), Variable("id", INTEGER)),
            aggregate("any_value", INTEGER, Variable("value", INTEGER)),
            aggregate("approx_distinct", BIGINT, Variable("value", INTEGER)),
        )) {
            assertThat(client.implementAggregation(session, aggregate, assignments)).describedAs(aggregate.toString()).isPresent
        }
    }

    @Test
    fun testUnexpectedOutputTypesStayLocal() {
        for (aggregate in listOf(
            aggregate("min", BIGINT, Variable("value", INTEGER)),
            aggregate("max", BIGINT, Variable("value", INTEGER)),
            aggregate("count", INTEGER, Variable("value", INTEGER), distinct = true),
            aggregate("min_by", BIGINT, Variable("value", INTEGER), Variable("id", INTEGER)),
            aggregate("any_value", BIGINT, Variable("value", INTEGER)),
            aggregate("approx_distinct", INTEGER, Variable("value", INTEGER)),
        )) {
            assertThat(client.implementAggregation(session, aggregate, assignments)).describedAs(aggregate.toString()).isEmpty
        }
    }

    @Test
    fun testNonJdbcAssignmentsStayLocal() {
        val foreign = mapOf<String, ColumnHandle>("value" to object : ColumnHandle {}, "id" to key)
        for (aggregate in listOf(
            aggregate("min", INTEGER, Variable("value", INTEGER)),
            aggregate("sum", BIGINT, Variable("value", INTEGER)),
            aggregate("count", BIGINT, Variable("value", INTEGER), distinct = true),
            aggregate("min_by", INTEGER, Variable("value", INTEGER), Variable("id", INTEGER)),
            aggregate("any_value", INTEGER, Variable("value", INTEGER)),
            aggregate("approx_distinct", BIGINT, Variable("value", INTEGER)),
        )) {
            assertThat(client.implementAggregation(session, aggregate, foreign)).describedAs(aggregate.toString()).isEmpty
        }
    }

    private fun aggregate(name: String, outputType: Type, vararg arguments: Variable, distinct: Boolean = false): AggregateFunction =
        AggregateFunction(name, outputType, arguments.toList(), emptyList(), distinct, Optional.empty())
}
