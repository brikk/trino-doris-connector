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

import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.GREATER_THAN_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.LESS_THAN_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.NOT_EQUAL_OPERATOR_FUNCTION_NAME

internal val COMPARISON_OPERATORS: Map<FunctionName, String> = mapOf(
    EQUAL_OPERATOR_FUNCTION_NAME to "=",
    NOT_EQUAL_OPERATOR_FUNCTION_NAME to "<>",
    LESS_THAN_OPERATOR_FUNCTION_NAME to "<",
    LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME to "<=",
    GREATER_THAN_OPERATOR_FUNCTION_NAME to ">",
    GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME to ">=",
)

/** The operator that keeps a comparison's meaning when its operands swap sides. */
internal fun flipComparison(operator: String): String = when (operator) {
    "<" -> ">"
    "<=" -> ">="
    ">" -> "<"
    ">=" -> "<="
    else -> operator
}
