# Remaining runtime contract fixes

Date: 2026-11-03. Worktree/branch: `trino-484`. Approved order: R-11, R-17,
R-08, R-09, with a commit/push between each. Trino 484, Doris
4.1.5-release-917b20be838, Connector/J 26.7.0, metadata 0.21.0, Kotlin 2.4.10
and JDK 25 remain fixed. This work does not authorize publication.

## R-11 — supported timeout ranges and shared normalization

`DorisTimeouts` is the single rendering/range policy used by catalog setters,
session-property validation, connection properties and scan setup:

- Connect: **1–2147483647 milliseconds**, inclusive. Sub-ms, zero and values
  above Connector/J's signed integer limit are rejected before connection use.
- Query: **1–2147483647 seconds**, inclusive, or unset. Catalog and session
  values share the same range; the session error uses INVALID_SESSION_PROPERTY.
- Valid fractional values round **up**, never to zero or below the requested
  budget. `1.1s` becomes `query_timeout=2` at both connection and scan setup.
  Unset still leaves the connection's native session timeout unchanged.

The query bound matches the golden Doris commit `917b20be838`'s
`SessionVariable.queryTimeoutS` / `checkQueryTimeoutValid`: positive Java int.
The maximum and fractional normalization are independently checked against live
native sessions. No server-global variable is changed. Configuration failures
occur at CREATE CATALOG; Airlift retains setter-range diagnostics in the cause
chain. These ranges do not create absolute DNS/authentication/TLS setup deadlines
or change explicit raw Connector/J URL-property semantics (K-04/K-05 remain).

Regressions cover zero/sub-unit/max/over-max values, fractional rounding, null
defaults, session decoding, real `DorisClient.buildSql` setup (including session
overrides), failed catalog/session statements, and native `@@query_timeout`.
The initial focused failures were test expectations about Airlift's top-level
message and serialized exception type; the assertion now checks the retained
configuration-cause message. Production range guards were not relaxed.

Focused qualification: **24 passed, zero failures/errors/skips**, detekt and
79-JAR assembly verification passed. This includes live max/fractional native
sessions and the real scan setup seam, not only policy-helper unit assertions.
Logs/XML: `~/.cache/trino-doris-484-final-runtime/r11-qualified.log`, `r11-xml/`.
The live checks explicitly opt into the R-15 disposable-cluster guard.
