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
import io.trino.plugin.base.expression.ConnectorExpressionRewriter
import io.trino.plugin.base.expression.ConnectorExpressionRule
import io.trino.plugin.base.expression.ConnectorExpressionRule.RewriteContext
import io.trino.plugin.jdbc.JdbcColumnHandle
import io.trino.plugin.jdbc.QueryParameter
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.spi.connector.ConnectorSession
import io.trino.spi.expression.Call
import io.trino.spi.expression.ConnectorExpression
import io.trino.spi.expression.Constant
import io.trino.spi.expression.FunctionName
import io.trino.spi.expression.StandardFunctions.COALESCE_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.GREATER_THAN_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.IS_NULL_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.LESS_THAN_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.NOT_EQUAL_OPERATOR_FUNCTION_NAME
import io.trino.spi.expression.StandardFunctions.NULLIF_FUNCTION_NAME
import io.trino.spi.expression.Variable
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
 * Batch-1 scalar expression pushdown (READ-ONLY-MAX; probe verdicts in
 * `NOTES-readonly-max-batch1.md`, evidence pins in [DorisPushdownEvidence]).
 *
 * The SCALAR VALUE tier: typed rules over a closed set of value types —
 * [DorisScalarValues.isValueType]: the exact non-text set plus VARCHAR (byte-fidelity
 * proven end-to-end; comparisons byte-exact per the P4 probe). CHAR (trailing-space
 * divergence) and REAL/DOUBLE (approximate + wire-Infinity hazard) never participate.
 * Every rule composes its children through the same rewriter, so e.g.
 * `coalesce(lower(v), 'x')` is pushable exactly when all parts are.
 *
 * CASE / IF are ENGINE-DENIED at Trino 483: the ConnectorExpression grammar has no CASE
 * form (`StandardFunctions` carries none) — the translator decomposes CASE into fragments
 * or folds simple predicate CASEs into comparisons upstream (probed live; the connector
 * never receives the conditional itself). Pinned in the batch-1 suite.
 */
internal object DorisScalarValues {
    /** Closed value-type set for the scalar tier. */
    fun isValueType(type: Type): Boolean = when (type) {
        is TinyintType, is SmallintType, is IntegerType, is io.trino.spi.type.BigintType,
        is DecimalType, is DateType, is BooleanType, is VarcharType,
        -> true
        is TimestampType -> type.precision <= DorisTypeMapping.MAX_DATETIME_PRECISION
        else -> false
    }

    /** Doris column-type text for [type] — drives both parameter binding and synthetic-column handles. */
    fun dorisTypeFor(type: Type): String? = when (type) {
        is TinyintType -> "tinyint"
        is SmallintType -> "smallint"
        is IntegerType -> "int"
        is io.trino.spi.type.BigintType -> "bigint"
        is BooleanType -> "boolean"
        is DateType -> "date"
        is TimestampType -> if (type.precision <= DorisTypeMapping.MAX_DATETIME_PRECISION) "datetime(${type.precision})" else null
        is DecimalType -> "decimal(${type.precision},${type.scale})"
        is VarcharType -> if (type.isUnbounded) "string" else "varchar(${type.boundedLength})"
        else -> null
    }
}

/** A column reference of a scalar value type (the leaf of every composition). */
internal class RewriteScalarVariable(
    private val quote: (String) -> String,
) : ConnectorExpressionRule<Variable, ParameterizedExpression> {
    override fun getPattern(): Pattern<Variable> =
        io.trino.plugin.base.expression.ConnectorExpressionPatterns.variable()

    override fun rewrite(expression: Variable, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        if (!DorisScalarValues.isValueType(expression.type)) {
            return Optional.empty()
        }
        val column = context.getAssignment(expression.name) as? JdbcColumnHandle ?: return Optional.empty()
        // CHAR columns map to CharType (excluded by isValueType); everything else that
        // reached a Variable of a value type is a native readable column.
        return Optional.of(ParameterizedExpression(quote(column.columnName), listOf()))
    }
}

/** A non-NULL constant of a scalar value type, bound as a prepared-statement parameter. */
internal class RewriteScalarConstant : ConnectorExpressionRule<Constant, ParameterizedExpression> {
    override fun getPattern(): Pattern<Constant> = constant()

    override fun rewrite(expression: Constant, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        val value = expression.value ?: return Optional.empty() // NULL literals stay upstream
        if (!DorisScalarValues.isValueType(expression.type)) {
            return Optional.empty()
        }
        if (value is Slice && sliceContainsNulByte(value)) {
            return Optional.empty() // 0x00 hazard policy, same as the domain guard
        }
        val dorisType = DorisScalarValues.dorisTypeFor(expression.type) ?: return Optional.empty()
        return Optional.of(
            ParameterizedExpression(
                "?",
                listOf(QueryParameter(DorisTypeMapping.toTypeHandle(dorisType), expression.type, Optional.of(value))),
            ),
        )
    }

    companion object {
        internal fun sliceContainsNulByte(slice: Slice): Boolean =
            (0 until slice.length()).any { slice.getByte(it) == 0.toByte() }
    }
}

/**
 * `$coalesce(a, b, ...)` -> `coalesce(a, b, ...)` and `$nullif(a, b)` -> `nullif(a, b)` —
 * registry-IDENTICAL ([DorisPushdownEvidence.COALESCE]/[DorisPushdownEvidence.NULLIF]);
 * live pins: NULL propagation, `nullif(a, a)` = NULL, and nullif's equality is byte-exact
 * case-SENSITIVE on text (`nullif('a','A')` = 'a' on both engines).
 */
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

/**
 * `year|month|day|hour|minute|second(timestamp(0..6))` -> the same-named Doris function —
 * registry-IDENTICAL for all six ([DorisPushdownEvidence.TEMPORAL_EXTRACT]); live-probed on
 * the 0000-01-01/9999-12-31 edges and every precision (fractional seconds truncate
 * identically). Trino returns BIGINT; Doris' narrower integer widens losslessly on read.
 * Comparisons like `year(dt) = 2026` are unwrapped into datetime range domains UPSTREAM
 * (UnwrapYearInComparison — probed: the predicate never reaches the connector), so these
 * rules matter for PROJECTION/GROUP BY shapes.
 */
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

/**
 * `lower(x)` / `upper(x)` -> same-named Doris functions — registry-IDENTICAL, and the
 * connector's divergence hunt CONFIRMED it: a battery of Unicode special-mapping
 * adversaries (µ U+00B5 -> Μ, ß unchanged, ΑΣ -> ασ with NO final sigma, Kelvin U+212A ->
 * k, Å U+212B -> å, long s U+017F -> S, DZ digraphs, dotted İ -> i, roman numerals,
 * fullwidth letters) maps IDENTICALLY on both engines — Doris matches Trino's
 * simple+special (java.lang.Character-family) mapping on every probed codepoint, including
 * the classic full-mapping traps where BOTH decline to expand. Identity is claimed on that
 * battery basis (pinned live); enabled in every mode as a value transform.
 */
internal class RewriteCaseFold(
    private val name: String,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = call()
        .with(functionName().equalTo(FunctionName(name)))
        .with(argumentCount().equalTo(1))

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        if (expression.type !is VarcharType || expression.arguments[0].type !is VarcharType) {
            return Optional.empty()
        }
        val child = context.defaultRewrite(expression.arguments[0])
        if (child.isEmpty) {
            return Optional.empty()
        }
        return Optional.of(ParameterizedExpression("$name(${child.get().expression()})", child.get().parameters()))
    }
}

/**
 * Trino `length(varchar)` (CHARACTERS) -> Doris `char_length` (characters) — NOT Doris
 * `length` (BYTES; the registry-DIVERGENT hazard, [DorisPushdownEvidence.LENGTH]). Live
 * pins: emoji = 1, CJK chars = 1 each, NFD combining sequences count per codepoint —
 * identical to Trino on every probed shape.
 */
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

/** Holder wiring the scalar rewriter into rules that need to rewrite through it. */
internal class DorisScalarRewriterHolder {
    lateinit var rewriter: ConnectorExpressionRewriter<ParameterizedExpression>

    fun rewrite(context: RewriteContext<ParameterizedExpression>, expression: ConnectorExpression): Optional<ParameterizedExpression> =
        rewriter.rewrite(context.session, expression, context.assignments)
}

/**
 * Predicate bridge: `<scalar> <cmp> <scalar>` where at least one side is a FUNCTION shape
 * (plain column-vs-literal comparisons are domain territory and never arrive here). Both
 * operands must rewrite through the scalar tier and be of value types — comparisons over
 * those are value-identical (exact types: P0 domain evidence; text: byte-exact, P4 probe).
 * TOP-LEVEL WHERE conjuncts only (not in the NOT/AND/OR value-safe tier): NULL cells drop
 * identically on both engines under a top-level conjunct.
 */
internal class RewriteScalarComparisonBridge(
    private val scalar: DorisScalarRewriterHolder,
) : ConnectorExpressionRule<Call, ParameterizedExpression> {
    override fun getPattern(): Pattern<Call> = PATTERN

    override fun rewrite(expression: Call, captures: Captures, context: RewriteContext<ParameterizedExpression>): Optional<ParameterizedExpression> {
        val left = expression.arguments[0]
        val right = expression.arguments[1]
        if (left !is Call && right !is Call) {
            return Optional.empty() // plain shapes belong to domain pushdown
        }
        if (!DorisScalarValues.isValueType(left.type) || !DorisScalarValues.isValueType(right.type)) {
            return Optional.empty()
        }
        // mode contract: NULL_ONLY pushes no string VALUE comparisons at all
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
        val operator = OPERATORS.getValue(expression.functionName)
        return Optional.of(
            ParameterizedExpression(
                "(${leftSql.get().expression()} $operator ${rightSql.get().expression()})",
                leftSql.get().parameters() + rightSql.get().parameters(),
            ),
        )
    }

    companion object {
        private val OPERATORS: Map<FunctionName, String> = mapOf(
            EQUAL_OPERATOR_FUNCTION_NAME to "=",
            NOT_EQUAL_OPERATOR_FUNCTION_NAME to "<>",
            LESS_THAN_OPERATOR_FUNCTION_NAME to "<",
            LESS_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME to "<=",
            GREATER_THAN_OPERATOR_FUNCTION_NAME to ">",
            GREATER_THAN_OR_EQUAL_OPERATOR_FUNCTION_NAME to ">=",
        )

        private val PATTERN: Pattern<Call> = call()
            .with(functionName().matching { it in OPERATORS.keys })
            .with(type().equalTo(BOOLEAN))
            .with(argumentCount().equalTo(2))
    }
}

/** Predicate bridge: `$is_null(<scalar function shape>)` -> `(<expr> IS NULL)` (value-identical child). */
internal class RewriteScalarIsNullBridge(
    private val scalar: DorisScalarRewriterHolder,
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
 * `starts_with(col, 'prefix')` -> `(col LIKE '<escaped-prefix>%')` — the
 * CONDITIONALLY_EQUIVALENT condition ([DorisPushdownEvidence.STARTS_WITH]): Doris has no
 * `starts_with`; the LIKE-prefix rendering is byte-exact (P4 probe: LIKE is memcmp
 * semantics) AND zone-map/index-eligible, unlike `left(col, n) = 'prefix'`. LIKE
 * metacharacters in the prefix are escaped (`%` -> `\%`, `_` -> `\_`, `\` -> `\\` —
 * live-proven: `'a%b_c' LIKE 'a\%b\_%'` matches, `'aXbYc'` does not). NULL input -> NULL
 * -> row dropped on both engines; empty prefix matches every non-NULL row on both.
 * Enabled in GUARDED and above (byte-exact per the tiered-GUARDED evidence); 0x00 prefixes
 * stay local per the standing hazard policy.
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
        val columnType = column.jdbcTypeHandle.jdbcTypeName().orElse(null) ?: return Optional.empty()
        if (DorisColumnType.parse(columnType).baseName !in setOf("varchar", "string", "text")) {
            return Optional.empty() // char excluded (trailing-space divergence)
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
        /** Escape Doris LIKE metacharacters so the prefix matches LITERALLY. */
        internal fun escapeForLikePrefix(prefix: String): String =
            prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        private val PATTERN: Pattern<Call> = call()
            .with(functionName().equalTo(FunctionName("starts_with")))
            .with(type().equalTo(BOOLEAN))
            .with(argumentCount().equalTo(2))
    }
}

/**
 * Generic PROJECTION rule: any Call the scalar tier can fully rewrite becomes a synthetic
 * derived column (typed via [DorisScalarValues.dorisTypeFor], so the read mapping matches
 * the Trino expression type exactly) — this is what lets `GROUP BY coalesce(...)`,
 * `GROUP BY year(...)` etc. compose with aggregate pushdown.
 */
internal class RewriteScalarProjection(
    private val scalar: DorisScalarRewriterHolder,
) : io.trino.plugin.base.projection.ProjectFunctionRule<io.trino.plugin.jdbc.JdbcExpression, ParameterizedExpression> {
    override fun getPattern(): Pattern<out ConnectorExpression> = call()

    override fun rewrite(
        handle: io.trino.spi.connector.ConnectorTableHandle,
        expression: ConnectorExpression,
        captures: Captures,
        context: io.trino.plugin.base.projection.ProjectFunctionRule.RewriteContext<ParameterizedExpression>,
    ): Optional<io.trino.plugin.jdbc.JdbcExpression> {
        val dorisType = DorisScalarValues.dorisTypeFor(expression.type) ?: return Optional.empty()
        val rewritten = context.rewriteExpression(expression)
        if (rewritten.isEmpty) {
            return Optional.empty()
        }
        return Optional.of(
            io.trino.plugin.jdbc.JdbcExpression(
                rewritten.get().expression(),
                rewritten.get().parameters(),
                DorisTypeMapping.toTypeHandle(dorisType),
            ),
        )
    }
}
