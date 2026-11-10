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

import io.trino.plugin.base.expression.ConnectorExpressionRewriter
import io.trino.plugin.base.expression.ConnectorExpressionRule.RewriteContext
import io.trino.plugin.jdbc.expression.ParameterizedExpression
import io.trino.spi.expression.ConnectorExpression
import java.util.Optional

/**
 * Lets a rule rewrite its children through a specific rule tier instead of the enclosing
 * rewriter. The rewriter is assigned after construction because the tier contains the rules
 * that hold this reference.
 */
internal class DorisRewriterHolder {
    lateinit var rewriter: ConnectorExpressionRewriter<ParameterizedExpression>

    fun rewrite(context: RewriteContext<ParameterizedExpression>, expression: ConnectorExpression): Optional<ParameterizedExpression> =
        rewriter.rewrite(context.session, expression, context.assignments)
}
