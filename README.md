# trino-doris-connector

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

A **read-only** [Trino](https://trino.io) 483 connector for [Apache Doris](https://doris.apache.org)
4.1.3, with **verified, typed predicate pushdown**. Doris speaks the MySQL wire protocol on its
FE, so a generic JDBC scan can connect — but that leaves Doris's native acceleration
(inverted indexes, sorted/partitioned storage, engine-native array functions) on the table and
risks silently-wrong results wherever Doris and MySQL/Trino semantics diverge. This connector
treats every pushdown as a **correctness claim proven against a live Doris 4.1.3 cluster**, not a
SQL-rendering convenience, and is **defense-in-depth read-only** so it can never mutate the target.

The baseline is pinned: **Apache Doris 4.1.3** + **Trino 483** + **MySQL Connector/J 9.7.0**. Any
Doris version change voids the semantic evidence and requires re-probing (see `dev-docs/`).

## Status

Read-only P0–P3 slice complete. **124 tests across 15 suites**, all green in a single
`./gradlew build`, most of them running live against the compose Doris 4.1.3 cluster.

| Phase | Content |
|---|---|
| **P0** | Protocol/type probe, ARRAY wire-decoder spike (GO-with-restrictions), stock-MySQL baseline, StarRocks connector audit, plugin-assembly proof, type & capability ledger |
| **P1a** | Base JDBC plugin, `information_schema`-driven metadata, ledger-exact scalar type mapping, streaming + cancellation, LIMIT pushdown |
| **P1b** | Read-only defense in depth: `ReadOnlyDorisClient` (76-signature reflection audit), connector access control, airtight `system.execute` denial, `trino_ro` SELECT-only fixture account, query-id remote tagging + audit-log proofs, server-side `query_timeout`, live cancellation |
| **P2a** | Native `ARRAY(T)` via a strict, fail-loud wire decoder over an unambiguous element allowlist (incl. nesting); string arrays kept non-native (proven byte-identical ambiguity) |
| **P2b** | Index-aware ARRAY predicate pushdown — `contains` / `arrays_overlap` (NULL-strip guarded) / `array_position` typed rules with live-proven NULL truth tables; bare-truthy rendering that keeps the Doris inverted index eligible (profile-proven) |
| **P3 slice** | Safe non-text TopN (native `NULLS FIRST/LAST`, `>65535` exactness proven), value-safe `NOT`/`AND`/`OR` composition restricted to value-identical operands (3VL truth tables pinned live), and dynamic `CREATE`/`DROP CATALOG` parity with validation-at-create semantics |

Key capabilities:

- **Native `ARRAY` support** with a strict wire decoder (fail-loud on ambiguity, exact precision).
- **Index-eligible pushdown** of `contains` / `arrays_overlap` / `array_position` — rendered so
  the Doris inverted index still fires (the naive `= 1` wrapper defeats it; proven via profile
  counters, `dev-docs/evidence/inverted-index-explain-p2b.md`).
- **Defense-in-depth read-only**: a forwarding client guard, connector access control, and
  `system.execute` denial — the connector fails loud rather than ever mutating Doris.
- **Dynamic catalogs**: `CREATE CATALOG` / `DROP CATALOG` with loud config validation at create
  and lazy reachability at first use.

Two decisions are explicitly **parked** (see `dev-docs/STATUS-2026-07-19.md`): the brikk-house
metadata artifact release that would let pushdown rules carry a pinned evidence citation (rules
today carry `PENDING RELEASE` citations backed by this repo's own live proofs), and multi-FE
failover (only the comma-list URL syntax is proven; a ≥3-FE cluster is needed to prove routing).

## Quickstart

Prerequisites: a JDK 25 toolchain (via [mise](https://mise.jdx.dev)) and Docker.

```bash
# 1. JDK 25 (mise reads ./mise.toml)
mise install

# 2. Bring up the live stock Apache Doris 4.1.3 cluster the live tests target
#    (FE MySQL on host 127.0.0.1:9130, HTTP on 8130, user root / no password).
./compose/up.sh

# 3. Build: compile + detekt + 124 tests + plugin assembly + assembly verification
./gradlew build
```

The live test suites connect to the running compose cluster and **fail loud** if it is down.
Tear the cluster down with `./compose/up.sh --down`.

## Plugin installation

The build assembles a Trino plugin directory (a directory of jars — **not** a shaded fat jar,
which is how Trino loads plugins):

```bash
./gradlew pluginAssemble        # -> build/trino-plugin/trino-doris-<version>/
./gradlew verifyPluginAssembly  # asserts no engine-provided/parent-first SPI jars leaked in,
                                # base-jdbc + Connector/J are bundled, exactly one guice jar
```

Copy the assembled directory into your Trino installation's plugin directory and restart the
coordinator/workers:

```bash
cp -r build/trino-plugin/trino-doris-<version> "$TRINO_HOME/plugin/"
```

## Catalog configuration

Static catalog — `etc/catalog/doris.properties`:

```properties
connector.name=doris
connection-url=jdbc:mysql://doris-fe-host:9030
connection-user=trino_ro
connection-password=***
```

Dynamic catalog at runtime (config is validated at `CREATE` time; reachability is checked lazily
on first use):

```sql
CREATE CATALOG doris USING doris
WITH (
  "connection-url" = 'jdbc:mysql://doris-fe-host:9030',
  "connection-user" = 'trino_ro',
  "connection-password" = '***'
);

-- ... query ...

DROP CATALOG doris;
```

## Documentation & evidence

Every capability in this connector is backed by a live-verified evidence report. Start here:

- `dev-docs/STATUS-2026-07-19.md` — state-of-the-connector summary and the parked decisions.
- `dev-docs/LEDGER-p0-type-and-capability.md` — authoritative type & capability ledger.
- `dev-docs/PLAN-trino-doris-extension.md` — the founding plan (copied from the origin monorepo).
- `dev-docs/NOTES-p*-implementation.md` — per-phase implementation notes.
- `dev-docs/evidence/` — raw probe output, the ARRAY wire-decoder spike, and the inverted-index
  EXPLAIN/profile proof.

## Build tooling

The build is **Gradle** (9.4.1, Kotlin DSL), inherited from the origin monorepo and trimmed to a
single-module layout. Gradle was retained deliberately: the connector's build needs a Trino BOM
`platform()` import, `runtimeClasspath` exclusions to keep parent-first SPI jars out of the plugin
directory, and a custom plugin-assembly verification task — none of which are expressible in the
Kotlin Toolchain (`0.11`) build model we considered. Revisit if that changes.

## Provenance

This connector was extracted, **with its git history preserved**, from the brikk monorepo
(`jvm/trino-doris`) via `git subtree split`. The scaffolding (Gradle wrapper, `build-logic`
convention plugins, version catalog) was adapted from the same monorepo's `jvm/` conventions. The
founding plan (`dev-docs/PLAN-trino-doris-extension.md`) travels with the repo so it no longer
depends on the monorepo for its founding document.

## License

Licensed under the [Apache License, Version 2.0](./LICENSE).
