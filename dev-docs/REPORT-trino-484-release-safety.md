# Trino 484 release-safety implementation

Golden stack stays fixed: Trino 484, Doris 4.1.5-release-917b20be838 (digest-pinned
FE/BE), Connector/J 26.7.0, released metadata 0.21.0, Kotlin 2.4.10, JDK 25.
Baseline checkpoint: `acbbfab`; the historical failing measurements remain in
`REPORT-trino-484-doris-4.1.5-lock.md`. No release is authorized by these steps.

## Step 1 — local Unicode case folding

Removed LOWER/UPPER admission and their obsolete active evidence entries (17
active pins remain). Functions containing either operation also stay in Trino;
ASCII-looking literals or VARCHAR metadata do not prove column contents ASCII.
Constant expressions already evaluated by Trino need no remote equivalent.

Regression coverage includes all four string modes, sharp-s, contextual sigma,
dotted I, NULL/ASCII controls, projections, predicates, grouping, nested
coalesce/length, and audit proof that neither function appears in remote SQL.
The native Trino expectations are unchanged; separate Doris observations now
explicitly record the measured divergence rather than claiming identity.
Other eligible derived grouping expressions still push.

Focused validation: **21 tests passed, zero failures/errors/skips**, plus
detekt. Initial test-only failures were corrected for bounded VARCHAR result
types and an aggregation-with-project plan shape; no result guard was relaxed.

```sh
JAVA_HOME=~/.local/share/mise/installs/java/25 ./gradlew test \
  --tests '*TestDorisCaseFoldLocal' --tests '*TestDorisB1ScalarPushdown' \
  --tests '*TestDorisPushdownEvidenceDrift' detekt --no-daemon --console=plain
```

Log: `/tmp/opencode/trino-484-step1-rerun.log`; XML:
`/tmp/opencode/trino-484-safety/step1-xml/`.

## Remaining approved work

2. Decline LEFT/RIGHT joins, preserving eligible INNER joins/base aggregates.
3. Bind cancellation to the scan's physical statement/session, not a busy HA
   connection proxy; verify real-driver failure/cleanup/failover schedules.
4. Full golden-stack, cancellation/multi-FE, and assembled-plugin qualification.

Major steps are committed and pushed to `trino-484`, never a release branch.
