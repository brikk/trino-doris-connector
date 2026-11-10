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
import io.airlift.slice.Slices.utf8Slice
import io.trino.matching.Captures
import io.trino.matching.Pattern
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.argumentCount
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.call
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.constant
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.functionName
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.type
import io.trino.plugin.base.expression.ConnectorExpressionPatterns.variable
import io.trino.plugin.base.expression.ConnectorExpressionRule
import io.trino.plugin.base.expression.ConnectorExpressionRule.RewriteContext
import io.trino.plugin.base.projection.ProjectFunctionRule
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.JdbcExpression
import io.trino.plugin.jdbc.QueryParameter
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.connector.ConnectorTableHandle
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.StandardFunctions.IS_NULL_FUNCTION_NAME
import io.trino.spi.expression.Variable
import io.trino.spi.type.BigintType
import io.trino.spi.type.BigintType.BIGINT
import io.trino.spi.type.BooleanType
import io.trino.spi.type.BooleanType.BOOLEAN
import io.trino.spi.type.DateType
import io.trino.spi.type.DecimalType
import io.trino.spi.type.IntegerType
import io.trino.spi.type.SmallintType
import io.trino.spi.type.TimestampType
import io.trino.spi.type.TinyintType
import io.trino.spi.type.Type
import io.trino.spi.type.VarcharType
import java.util.Optional

/**
 * The scalar rules accept only these value types: exact numerics, DATE, DATETIME, BOOLEAN and
 * VARCHAR, which Doris compares byte for byte like Trino. CHAR (trailing spaces) and REAL/DOUBLE
 * (approximate, Infinity on the wire) are excluded. Each rule rewrites its children through the
 * same rewriter, so an expression is pushed only if every part of it is.
 */
internal object DorisScalarValues {
    fun isValueType(type: Type): Boolean = when (type) {
        is TinyintType, is SmallintType, is IntegerType, is BigintType,
        is DecimalType, is DateType, is BooleanType, is VarcharType,
        -> true
        is TimestampType -> type.precision <= DorisTypeMapping.MAX_DATETIME_PRECISION
        else -> false
    }

    /** COLUMN_TYPE text for parameter type handles and synthetic columns. */
    fun dorisTypeFor(type: Type): String? = when (type) {
        is TinyintType -> "tinyint"
        is SmallintType -> "smallint"
        is IntegerType -> "int"
        is BigintType -> "bigint"
        is BooleanType -> "boolean"
        is DateType -> "date"
        is TimestampType -> if (type.precision <= DorisTypeMapping.MAX_DATETIME_PRECISION) "datetime(${type.precision})" else null
        is DecimalType -> "decimal(${type.precision},${type.scale})"
        is VarcharType -> if (type.isUnbounded) "string" else "varchar(${type.boundedLength})"
        else -> null
    }
}

internal class RewriteScalarVariable(
    private val typeMapping: DorisTypeMapping,
    private val quote: (String) -> String,
) : ConnectorExpressionRule<Variable, ParameterizedExpression> {
    override fun getPattern(): Pattern<Variable> = variable()

    override fun rewrite(expression: Variable, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        if (!DorisScalarValues.isValueType(expression.type)) {
            return Optional.empty()
        }
        val column = context.getAssignment(expression.name) as? JdbcColumnHandle ?: return Optional.empty()
        val remoteType = column.jdbcTypeHandle.jdbcTypeName().orElse(null) ?: return Optional.empty()
        if (expression.type is VarcharType && !DorisColumnType.isNativeString(column)) {
            return Optional.empty()
        }
        val mappedType = typeMapping.toColumnMapping(context.session, remoteType).map { it.type }.orElse(null)
        if (expression.type != column.columnType || expression.type != mappedType) {
            return Optional.empty()
        }
        return Optional.of(ParameterizedExpression(quote(column.columnName), listOf()))
    }
}

internal class RewriteScalarConstant : ConnectorExpressionRule<Constant, ParameterizedExpression> {
    override fun getPattern(): Pattern<Constant> = constant()

    override fun rewrite(expression: Constant, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        val value = expression.value ?: return Optional.empty()
        if (!DorisScalarValues.isValueType(expression.type)) {
            return Optional.empty()
        }
        if (!DorisPushdownControllers.isWithinTemporalRange(expression.type, value)) {
            return Optional.empty()
        }
        if (value is Slice && DorisPushdownControllers.containsNulByte(value)) {
            return Optional.empty() // same 0x00 rule as the VARCHAR domain guard
        }
        val dorisType = DorisScalarValues.dorisTypeFor(expression.type) ?: return Optional.empty()
        return Optional.of(
            ParameterizedExpression(
                "?",
                listOf(QueryParameter(DorisTypeMapping.toTypeHandle(dorisType), expression.type, Optional.of(value))),
            ),
        )
    }
}

internal class RewriteScalarFunctionByName(
    private val trinoName: FunctionName,
    private val dorisName: String,
    private val arity: IntRange,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = call().with(functionName().equalTo(trinoName))

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        if (expression.arguments.size !in arity || !DorisScalarValues.isValueType(expression.type)) {
            return Optional.empty()
        }
        val children = expression.arguments.map { argument ->
            val child = context.defaultRewrite(argument)
            if (child.isEmpty) {
                return Optional.empty()
            }
            child.get()
        }
        val sql = children.joinToString(prefix = "$dorisName(", postfix = ")", separator = ", ") { it.expression() }
        return Optional.of(ParameterizedExpression(sql, children.flatMap { it.parameters() }))
    }
}

internal class RewriteTemporalExtract(
    private val name: String,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = call()
        .with(functionName().equalTo(FunctionName(name)))
        .with(type().equalTo(BIGINT))
        .with(argumentCount().equalTo(1))

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        val argumentType = expression.arguments[0].type
        if (argumentType !is TimestampType || argumentType.precision > DorisTypeMapping.MAX_DATETIME_PRECISION) {
            return Optional.empty()
        }
        val child = context.defaultRewrite(expression.arguments[0])
        if (child.isEmpty) {
            return Optional.empty()
        }
        return Optional.of(ParameterizedExpression("$name(${child.get().expression()})", child.get().parameters()))
    }
}

/** Trino `length` counts characters; Doris `length` counts bytes, so this renders `char_length`. */
internal class RewriteCharLength : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = call()
        .with(functionName().equalTo(FunctionName("length")))
        .with(type().equalTo(BIGINT))
        .with(argumentCount().equalTo(1))

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        if (expression.arguments[0].type !is VarcharType) {
            return Optional.empty()
        }
        val child = context.defaultRewrite(expression.arguments[0])
        if (child.isEmpty) {
            return Optional.empty()
        }
        return Optional.of(ParameterizedExpression("char_length(${child.get().expression()})", child.get().parameters()))
    }
}

/**
 * Comparisons with a function call on at least one side; plain column comparisons are domains.
 * Pushed only as a top-level WHERE conjunct, where a NULL result drops the row on both engines.
 */
internal class RewriteScalarComparisonBridge(
    private val scalar: DorisRewriterHolder,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        val left = expression.arguments[0]
        val right = expression.arguments[1]
        if (left !is Call && right !is Call) {
            return Optional.empty()
        }
        if (!DorisScalarValues.isValueType(left.type) || !DorisScalarValues.isValueType(right.type)) {
            return Optional.empty()
        }
        if ((left.type is VarcharType || right.type is VarcharType) &&
            DorisSessionProperties.getStringPushdownMode(context.session) == DorisStringPushdownMode.NULL_ONLY
        ) {
            return Optional.empty()
        }
        val leftSql = scalar.rewrite(context, left)
        val rightSql = scalar.rewrite(context, right)
        if (leftSql.isEmpty || rightSql.isEmpty) {
            return Optional.empty()
        }
        val operator = COMPARISON_OPERATORS.getValue(expression.functionName)
        return Optional.of(
            ParameterizedExpression(
                "(${leftSql.get().expression()} $operator ${rightSql.get().expression()})",
                leftSql.get().parameters() + rightSql.get().parameters(),
            ),
        )
    }

    companion object {
        private val PATTERN: Pattern<Call> = call()
            .with(functionName().matching { it in COMPARISON_OPERATORS.keys })
            .with(type().equalTo(BOOLEAN))
            .with(argumentCount().equalTo(2))
    }
}

internal class RewriteScalarIsNullBridge(
    private val scalar: DorisRewriterHolder,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        val argument = expression.arguments[0] as? Call ?: return Optional.empty() // plain IS NULL is a domain
        if (!DorisScalarValues.isValueType(argument.type)) {
            return Optional.empty()
        }
        val child = scalar.rewrite(context, argument)
        if (child.isEmpty) {
            return Optional.empty()
        }
        return Optional.of(ParameterizedExpression("(${child.get().expression()} IS NULL)", child.get().parameters()))
    }

    companion object {
        private val PATTERN: Pattern<Call> = call()
            .with(functionName().equalTo(IS_NULL_FUNCTION_NAME))
            .with(type().equalTo(BOOLEAN))
            .with(argumentCount().equalTo(1))
    }
}

/**
 * Rendered as `LIKE '<prefix>%'` with LIKE metacharacters escaped: Doris LIKE compares bytes as
 * Trino does, and unlike `left(col, n) = prefix` it can use zone maps and indexes.
 */
internal class RewriteStartsWith(
    private val quote: (String) -> String,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun isEnabled(session: ConnectorSession): Boolean =
        DorisSessionProperties.getStringPushdownMode(session) != DorisStringPushdownMode.NULL_ONLY

    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        val variable = expression.arguments[0] as? Variable ?: return Optional.empty()
        if (variable.type !is VarcharType) {
            return Optional.empty()
        }
        val column = context.getAssignment(variable.name) as? JdbcColumnHandle ?: return Optional.empty()
        if (!DorisColumnType.isNativeString(column)) {
            return Optional.empty() // CHAR excluded (trailing spaces)
        }
        val prefix = (expression.arguments[1] as? Constant)?.takeIf { it.type is VarcharType }?.value as? Slice
            ?: return Optional.empty()
        val prefixText = prefix.toStringUtf8()
        if (prefixText.contains('\u0000')) {
            return Optional.empty()
        }
        val pattern = utf8Slice(escapeForLikePrefix(prefixText) + "%")
        return Optional.of(
            ParameterizedExpression(
                "(${quote(column.columnName)} LIKE ?)",
                listOf(QueryParameter(column.jdbcTypeHandle, VarcharType.VARCHAR, Optional.of(pattern))),
            ),
        )
    }

    companion object {
        internal fun escapeForLikePrefix(prefix: String): String =
            prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        private val PATTERN: Pattern<Call> = call()
            .with(functionName().equalTo(FunctionName("starts_with")))
            .with(type().equalTo(BOOLEAN))
            .with(argumentCount().equalTo(2))
    }
}

/** The column type comes from [DorisScalarValues.dorisTypeFor], so its read mapping yields the expression's Trino type. */
internal class RewriteScalarProjection : ProjectFunctionRule<JdbcExpression, ParameterizedExpression> {
    override fun getPattern(): Pattern<out ConnectorExpression> = call()

    override fun rewrite(
        handle: ConnectorTableHandle,
        expression: ConnectorExpression,
        captures: Captures,
        context: ProjectFunctionRule.RewriteContext<ParameterizedExpression>,
    ): Optional<JdbcExpression> {
        val dorisType = DorisScalarValues.dorisTypeFor(expression.type) ?: return Optional.empty()
        val rewritten = context.rewriteExpression(expression)
        if (rewritten.isEmpty) {
            return Optional.empty()
        }
        return Optional.of(
            JdbcExpression(
                rewritten.get().expression(),
                rewritten.get().parameters(),
                DorisTypeMapping.toTypeHandle(dorisType),
            ),
        )
    }
}
