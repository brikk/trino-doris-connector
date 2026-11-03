# Metadata contract

The production registry dependency is intentional: `dev.brikk.house:brikk-sql-metadata-jvm:0.21.0`.
Its version is in [the version catalog](../gradle/libs.versions.toml); binary SHA
and tuple drift checks live in
[TestDorisPushdownEvidenceDrift](../test/src/dev/brikk/trino/doris/TestDorisPushdownEvidenceDrift.kt).
Do not replace the immutable release with a mutable snapshot or automatically bump it.

At connector construction, `DorisPushdownEvidence.verifyAgainstRegistry` checks
the active entries' direction, source/target, verdict and provenance. Drift
refuses construction rather than silently changing pushdown semantics. The index
currently has 17 active pins; not every connector rule is a distinct registry entry.
Documentation-string consistency checks are not proofs of actual SQL rendering.

Changing a registry verdict does not itself authorize a rule. Admission still
depends on the connector's type/wire/nullability/path/value guard and live tests.
In particular, JSON scalar equality has conditional, restricted admission; strict
JSON reads remain distinct from text equality predicates.

## Unicode case folding

The registry still contains historical LOWER/UPPER identity entries. The consumer
does not admit either rule or include it among active pins. On the pinned stack:

| Expression | Trino 484 | Doris 4.1.5 |
|---|---|---|
| `upper('ß')` | `ß` | `SS` |
| `lower('ΑΣ')` | `ασ` | `ας` (final sigma) |
| `lower('İ')` | `i` | `i` + U+0307 |

Column-backed all-mode tests are in
[TestDorisCaseFoldLocal](../test/src/dev/brikk/trino/doris/TestDorisCaseFoldLocal.kt).
The evidence is handed to maintainers in
[brikk-sql#1](https://github.com/brikk/brikk-sql/issues/1). This is not a dependency
on another team's release: local evaluation already protects the connector.
An upstream correction does not authorize a version bump or re-admission without
a separate decision and qualification.

Original evidence and qualification are retained in the
[golden-stack measurements](archive/REPORT-trino-484-doris-4.1.5-lock.md) and
[safety implementation](archive/REPORT-trino-484-release-safety.md). The small
redirects at their published paths preserve links in the upstream issue.

The [fulfilled additive API request](archive/HANDOFF-brikk-sql-metadata-request.md)
is history, not an outstanding TODO. Removing the sanctioned runtime dependency
is an architecture/product decision, not routine dependency cleanup.
