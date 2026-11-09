# Metadata contract

The pushdown rules were proven against `dev.brikk.house:brikk-sql-metadata-jvm:0.21.0`,
a test-scope dependency; the plugin does not bundle it. Its version is in
[the version catalog](../gradle/libs.versions.toml).
Do not replace the immutable release with a mutable snapshot or automatically bump it.

[TestDorisPushdownEvidenceDrift](../test/src/dev/brikk/trino/doris/TestDorisPushdownEvidenceDrift.kt)
pins the artifact's file name and SHA-256, and for each of the 17 pushed functions the
registry's source/target, verdict and provenance. Registry drift fails the build; it
never changes what the connector pushes. Not every connector rule is a distinct registry entry.

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
is history, not an outstanding TODO.
