# Guidance for agents

## Detekt advisories

Five detekt rules are advisory. They are reported as warnings and never fail the build or CI:

| Rule | Review signal |
|---|---|
| `MaxLineLength` | 120 characters |
| `LongMethod` | 60 lines |
| `CognitiveComplexMethod` | complexity 15 |
| `CyclomaticComplexMethod` | complexity 14 |
| `ReturnCount` | 4 returns, not counting guard clauses and returns from lambdas |

These numbers are review signals, not mandatory limits. Every other enabled detekt rule is
enforced: its findings have severity error and fail the build.

Prioritize readable, cohesive code over metric compliance. Do not wrap lines, split methods,
add trivial helpers, introduce mutable state, or rewrite clear control flow solely to clear an
advisory. Extract helpers when they express meaningful responsibilities, isolate invariants, or
remove duplication. Advisories require neither suppressions nor baseline entries.

The configuration is `config/detekt/detekt.yml` (`severity: warning` on the five rules) with
`failOnSeverity = FailOnSeverity.Error` in `build.gradle.kts`. This is detekt 2.x behaviour;
check how non-blocking findings work before applying it to another detekt version.
