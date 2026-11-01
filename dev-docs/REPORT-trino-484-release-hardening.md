# Trino 484 release hardening

Date: 2026-11-01. Branch/worktree: `trino-484`. Continues the approved batch
after [safety qualification](REPORT-trino-484-release-safety.md), not a release
publication decision. Trino 484 / Doris 4.1.5-release-917b20be838 / Connector/J
26.7.0 / metadata 0.21.0 / Kotlin 2.4.10 / JDK 25 remain fixed.

## Milestone 1 — R-06 / R-07

All nine published ports across single-FE, three-FE and manual Trino Compose
topologies bind to `127.0.0.1` by default. Sharing requires an explicit
`DORIS_BIND_ADDRESS` or `TRINO_BIND_ADDRESS` override. Documentation recommends
SSH tunnels/authenticated deployments and warns against adding production
credentials to a shared unauthenticated sandbox. This is host-port hardening,
not isolation from Docker peers or local users.

`test/tooling/test_compose_bindings.py` checks six rendered configurations
(three default, three explicit-address overrides). The running golden FE was
recreated with its retained volumes: Docker inspection confirms only
`127.0.0.1:8130` and `127.0.0.1:9130`. No unrelated workloads were changed.

Doris 4.1.5 still rejects `SELECT 'a_b' LIKE '%é_%' ESCAPE 'é'` with
`like escape character must be a single ascii character`. The rule now admits
only a single non-NUL ASCII escape, keeping unsupported escapes local. Durable
regressions compare accented and CJK escapes against
pushdown-disabled Trino in every mode, pin the native rejection, retain a local
filter and check remote SQL. Existing ASCII and no-escape controls remain.
Supplementary escapes are rejected by Trino 484 itself; the test also checks
that the same native Trino error is preserved with pushdown enabled/disabled.

Focused validation: **14 tests passed, zero failures/errors/skips**, plus detekt.
The first disk-backed run found a test assumption error: supplementary escapes
are invalid in Trino itself. The valid-case matrix was corrected to CJK and the
supplementary case retained as an error-parity assertion; no production guard
was relaxed. Final log/XML: `~/.cache/trino-doris-484-hardening/r06-r07-final.log`
and `r06-r07-xml/`. The rendered Compose regression also passes.

## Validation environment

The initial focused attempt failed during Kotlin incremental-cache backup with
`Disk quota exceeded` on the host's `/tmp` tmpfs. This is not an assertion
failure. Its log remains at `/tmp/opencode/trino-484-hardening-r06-r07.log`.
Subsequent runs use disk-backed scratch at
`~/.cache/trino-doris-484-hardening/`, with `JAVA_TOOL_OPTIONS` pointing
`java.io.tmpdir` there and Kotlin compilation in-process. No unrelated temporary
files were deleted. Product dependency pins and test JVM heap are unchanged.
