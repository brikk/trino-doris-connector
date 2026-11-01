# Cancellation targeting and disposable-test safety

Date: 2026-11-01. Worktree/branch: `trino-484`. Golden Trino 484 / Doris
4.1.5-release-917b20be838 / Connector/J 26.7.0 / metadata 0.21.0 / Kotlin
2.4.10 / JDK 25 are unchanged. No publication or release-version decision.

## R-10 — connector-owned leading token

`DorisClient.buildSql` now prefixes each scan with its unique statement comment,
before generated SQL and tracing comments. The cancellation candidate matcher
requires that exact leading comment, its whitespace boundary, and the complete
UUID-shaped token. It no longer searches arbitrary SQL text. Tracing by Trino
query ID remains unchanged and never becomes cancellation authority.

Tests reject the exact comment inside single/double-quoted literals, quoted
identifiers, escaped/doubled strings, line/block comments, trailing positions,
and sibling-token SQL. They retain genuine generated/traced queries, including
quoted parameter values. This boundary is not a cryptographic authentication
scheme: deliberately issuing SQL with a copied leading token is outside the
accidental audit-search classification fix.

The production-helper scripted regression includes an unrelated audit-search
row on every processlist scan and proves no KILL is sent for it. Existing delayed
visibility, unassigned QueryIds, retries, sibling isolation and repeat-kill
controls remain. Production scan tests assert the generated token begins at
offset zero; real-driver helper/abort, streaming, and slow cancellation pass.

Focused qualification: **37 passed, zero failures/errors/skips**, detekt and
79-JAR assembly verification passed; slow cancellation enabled. Log/XML under
`~/.cache/trino-doris-484-r10-r15/`: `r10-qualification.log`, `r10-xml/`.
No new cross-FE replay is claimed at this checkpoint. The old diagnostic now
asserts rejection of the literal case rather than pinning the resolved defect.

## R-15 — next implementation milestone

R-10 is committed/pushed before starting R-15 implementation. The latter will
require explicit mutation/failover authorization and a disposable cluster
identity at shared connection/action boundaries; endpoint reachability and a
matching Doris version alone will not authorize fixture rebuilds or container
stops.
