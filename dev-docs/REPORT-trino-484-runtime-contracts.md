# Remaining runtime contract fixes

Date: 2026-11-03. Worktree/branch: `trino-484`. Approved order: R-11, R-17,
R-08, R-09, with a commit/push between each. Trino 484, Doris
4.1.5-release-917b20be838, Connector/J 26.7.0, metadata 0.21.0, Kotlin 2.4.10
and JDK 25 remain fixed. This work does not authorize publication.

Separate implementation checkpoints, committed and pushed in the approved order:

| Finding | Commit | Focused result |
|---|---|---|
| R-11 | `60a75e3` | 24 passed |
| R-17 | `fdb8c7c` | 19 passed |
| R-08 | `f2be88f` | 29 passed |
| R-09 | `a065fa3` | 42 passed, including scheduling stress and slow cancellation |

Each focused run also passed detekt/assembly verification. These overlapping
runs are not added together as one unique test count; final staged results follow.

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

## R-17 — supported JDBC URL types and effective database settings

Validation uses Connector/J 26.7.0's lexical `ConnectionUrlParser` and pure type
classification only. It does not construct `ConnectionUrl`, load a transform or
profile, resolve SRV/DNS, or connect. Normal single/failover/load-balance/
replication JDBC remain supported; X Protocol is rejected. DNS-SRV uses the
ordinary JDBC URL option `dnsSrv=true`, not a direct `jdbc:mysql+srv:` scheme.

The no-database check covers the decoded path and decoded global/per-host
properties, with case-insensitive DBNAME recognition. Any nonempty DBNAME is
rejected, even amid conflicting case variants; empty DBNAME remains permitted.
Incidental text in credentials/other values is not mistaken for a property.
Malformed percent encoding/host syntax fails closed instead of escaping validation.

Nonempty `propertiesTransform` and `useConfigs` are deliberately unsupported
at the catalog URL boundary: their effective settings cannot be verified without
executing/loading them. This is a conservative compatibility restriction, not
a claim that arbitrary custom driver code is sandboxed. The internal helper
driver's independent property-preservation tests are not a catalog admission rule.

Regressions cover protocol families, IPv6, missing/encoded/malformed inputs,
global/per-host/encoded/mixed-case DBNAME, incidental text, config mutation,
non-resolving SRV validation, and a transform-constructor/invocation sentinel.
CREATE CATALOG exercises the same restrictions against the real module wiring.
Focused qualification: **19 passed, zero failures/errors/skips**, detekt and
79-JAR assembly verification passed. Log/XML:
`~/.cache/trino-doris-484-final-runtime/r17-focused.log`, `r17-xml/`.

## R-08 — statistics policy above both caches

The pinned 484 factory adds a per-transaction `CachingJdbcClient` above the
shared catalog cache. `DorisJdbcMetadataFactory` preserves those upstream
caches/listeners/identity settings and inserts the session policy at the outer
metadata statistics boundary. Disabled sessions return unknown **before either
cache or delegate is consulted**, preventing both warmed-estimate leakage and
disabled-first missing-value poisoning. Enabled sessions retain their normal
cache behavior. The existing below-cache guard remains defense in depth.

Unit tests use both real caching layers and a counted delegate, proving enabled
hits are preserved across metadata transactions and disabled requests never
reach the delegate or insert empty results (missing-value caching on and off).
Live tests use positive TTL/missing caching in both directions and a separate
catalog defaulting to disabled with explicit enabled-session overrides. They
also pin unknown column estimates and unchanged data results. Existing statistics,
join cost/INNER controls and read-only guards are included in qualification.

Focused qualification: **29 passed, zero failures/errors/skips**, detekt and
79-JAR assembly verification passed. Log/XML:
`~/.cache/trino-doris-484-final-runtime/r08-qualified.log`, `r08-xml/`.
An initial test compile typo (`Estimate.of` needs a Double) was corrected before
the successful run. Mixed-age estimates and malformed statistics isolation
(K-02/K-03) remain separate; this is a policy/cache-boundary fix, not a freshness
guarantee or a general upstream cache rewrite.

## R-09 — scheduled cancellation probes

The helper no longer sleeps through a 15-second retry loop while holding a
worker/connection. Each attempt opens one helper, widens visibility, makes one
processlist/KILL probe, then closes JDBC resources before scheduling the next
attempt. The original dispatch deadline is passed to **every** connection open;
retry delay/queue time never renews it. A single retry scheduler performs no JDBC
work in production; the existing eight-worker, bounded-queue executor does probes.

A shared 72-request admission budget counts running, queued **and delayed**
logical requests across catalogs. Rejections remain non-blocking/best-effort,
and terminal failure, release or expiry returns admission exactly once. Delayed
work cannot grow an unbounded timer queue while worker slots happen to be free.
At most eight helper connections are active, and their watchdog stays armed
through resource close. The independent physical-abort fallback is unchanged.

Observation state persists across probes: an empty result before a token has
ever been seen is not proof of release, even when the scan execution has ended.
Completion is sampled before scanning. Unassigned/invalid QueryIds, repeated
early no-op kills, sibling isolation, strict leading-token admission and original
queue deadlines remain covered. Tests now count per-probe resource closes,
distinct from completion of the whole logical request.

This trades reconnect/setup work for useful worker/connection capacity. It is
not a connection-rate benchmark, a guarantee of admission above the fixed bound,
or an absolute DNS/authentication/custom-socket deadline (K-04/K-05 remain).

New production-dispatcher stress models exercise three rounds of eight absent
finished scans plus a live ninth; 72 delayed requests plus rejected overflow;
unchanged deadlines across reconnects; and worker/scheduler rejection cleanup.
Focused qualification: **42 passed, zero failures/errors/skips**, including slow
cancellation, detekt and 79-JAR assembly verification. Log/XML:
`~/.cache/trino-doris-484-final-runtime/r09-qualified.log`, `r09-xml/`.
Initial scaffolding corrections split an overly complex fake and moved its
remote-state reset after logical-request completion. No production deadline,
admission bound, delayed-visibility guard or assertion threshold was loosened.

## Final staged qualification pending

Implementation milestones are committed/pushed in the approved order. The final
clean single-FE build, separate overlay replay, tooling guards and packaged-plugin
smoke will be recorded here. Focused passes above are not presented as a completed
aggregate run. No release is authorized by any of these checks.

The first isolated-plugin smoke found an admission mismatch hidden by standalone
predicate tests: Trino 484's inherited BaseJdbcConfig URL pattern rejects direct
`jdbc:mysql+srv:` schemes. The validator now reuses that upstream constraint and
its flags, and module tests cover rejection plus successful non-resolving CREATE
with `jdbc:mysql://.../?dnsSrv=true`. No new URL family was enabled. The initial
smoke failure is retained separately; qualification is rerun after this alignment.
All 19 URL/config/module checks, detekt and assembly verification passed again
with the inherited-pattern constraint included (`r17-base-constraint.log`).
