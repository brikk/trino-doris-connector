# trino-doris MANUAL smoke sandbox

A single **Trino 483** coordinator with **OUR `trino-doris` plugin installed** and
**dynamic catalog management** enabled, so you can log into the CLI and add **your
own** Doris server at runtime with `CREATE CATALOG ... USING doris WITH (...)` — no
rebuild, no restart.

This is the **first-ever production-shaped load of the plugin**: Trino's
`PluginManager` `ServiceLoader`-scans the mounted plugin dir under
`/usr/lib/trino/plugin/`, exactly as a real install would — *not* the in-JVM test
classpath the automated suites use. If the connector works here, it works when a
real operator drops the assembled dir into a real Trino.

## Quick start

```sh
./up.sh              # assemble plugin if missing, start Trino, wait healthy, print cheat sheet
./up.sh --rebuild    # force `./gradlew pluginAssemble` even if the dir already exists
./down.sh            # tear down (containers + volumes)
```

`up.sh` uses the repo-root gradle wrapper with **mise JDK 25**
(`JAVA_HOME=~/.local/share/mise/installs/java/25`) to run `pluginAssemble`, mounts
the resulting `build/trino-plugin/trino-doris-<version>` dir **read-only** into
`/usr/lib/trino/plugin/trino-doris`, and waits until
`docker exec trino-doris-manual trino --execute 'SELECT 1'` succeeds.

Trino HTTP + CLI: **http://localhost:18080** (host `18080` → container `8080`).

## Log in and add YOUR Doris

```sh
docker exec -it trino-doris-manual trino
```

Or connect **externally** — the sandbox publishes plain HTTP on host port `18080` with
**no authentication configured** (no authenticator at all, not merely a blank password):

- CLI: `trino --server http://<host>:18080 --user whoever`
- Web UI: `http://<host>:18080` (any username)
- **JDBC** (DBeaver/DataGrip/code): use `sessionUser` in the URL and do **not** fill in
  user/password fields:

  ```
  jdbc:trino://<host>:18080?sessionUser=trino
  ```

  Driver trap (verified 2026-07-19): supplying credentials via the `user`/`password`
  properties — which most GUI tools do, sending an empty password — engages the Trino
  JDBC driver's authentication path, and that path **hard-requires SSL** regardless of
  the password being empty. `sessionUser=` sets your identity without touching the
  credential path, which is the only shape that works against a no-auth server.

```sql
-- YOUR OWN external Doris server (host.docker.internal reaches a Doris on THIS
-- machine; use a LAN IP for another host). Give Trino a SELECT-only account first:
--   DROP USER IF EXISTS 'trino_ro'@'%';
--   CREATE USER 'trino_ro'@'%' IDENTIFIED BY 'CHANGE_ME';
--   GRANT SELECT_PRIV ON internal.*.* TO 'trino_ro'@'%';
CREATE CATALOG doris_prod USING doris
WITH (
    "connection-url"      = 'jdbc:mysql://host.docker.internal:9030',
    "connection-user"     = 'trino_ro',
    "connection-password" = 'CHANGE_ME'
);

SHOW SCHEMAS FROM doris_prod;
SELECT ...;
DROP CATALOG doris_prod;
```

The `connection-url` must be a `jdbc:mysql://host:9030` FE address **without** a
database path (the connector rejects `jdbc:mysql://host:9030/db` at CREATE time).
Multiple FEs: `jdbc:mysql://h1:9030,h2:9030,h3:9030`.

## Image-config gotchas found (trinodb/trino:483)

- The stock image ships `config.properties` with
  `catalog.management=${ENV:CATALOG_MANAGEMENT}` — it **requires** a
  `CATALOG_MANAGEMENT` env var and refuses to start if it is unset/empty. Rather
  than rely on that indirection we **replace** the file with `etc/config.properties`,
  which pins `catalog.management=dynamic` and `catalog.store=memory` in-file (visible,
  auditable, self-contained).
- `catalog.store=memory` means dynamically created catalogs live only for the life
  of the container — ideal for a manual sandbox (no stale catalog files). Switch to
  `catalog.store=file` + `catalog.config-dir=/etc/trino/catalog` if you want them to
  survive a container restart.
- The container is attached to **two** networks: its own bridge (outbound to your
  LAN / `host.docker.internal`) **and** the local compose Doris network
  `trino-doris-dev_doris-net` (declared `external`) so the bundled cluster at
  `172.30.81.10:9030` works with zero wiring. If that network doesn't exist,
  `up.sh` **fails fast** with an explicit message — see below.

### Optional local-Doris network

The bundled compose Doris (`../docker-compose.yml`) publishes its FE inside the
`trino-doris-dev_doris-net` network at `172.30.81.10:9030`. `up.sh` requires that
network to be present (it declares it `external`). If you don't want the local
cluster, edit `docker-compose.yml` to drop the `doris-net` attachment and instead
wire it after the fact only when needed:

```sh
docker network connect trino-doris-dev_doris-net trino-doris-manual
```

## Worked example (real transcript)

Captured against the live local compose Doris cluster (stock Apache Doris 4.1.3,
FE at `172.30.81.10:9030` on `trino-doris-dev_doris-net`).

### 1. Plugin loaded via the production ServiceLoader path

`up.sh` output:

```
[trino-doris-manual] Plugin dir: .../build/trino-plugin/trino-doris-483-1-ALPHA
[trino-doris-manual]   ServiceLoader SPI: dev.brikk.trino.doris.DorisPlugin
[trino-doris-manual] Starting Trino 483 with the plugin dir mounted read-only…
[trino-doris-manual] Trino is up.
```

Trino server log — the plugin manager scanning `/usr/lib/trino/plugin`:

```
io.trino.server.PluginManager  -- Loading plugin trino-doris --
io.trino.server.PluginManager  Installing dev.brikk.trino.doris.DorisPlugin
io.trino.server.PluginManager  Registering connector doris
io.trino.server.PluginManager  -- Finished loading plugin trino-doris --
```

This is the significance: the connector was discovered by `ServiceLoader` from the
mounted plugin dir's `META-INF/services/io.trino.spi.Plugin`, isolated in its own
plugin classloader — the real deployment code path, exercised here for the first time.

### 2. CREATE CATALOG against the local Doris (dynamic catalog)

```sql
trino> CREATE CATALOG doris_local USING doris
    -> WITH ("connection-url" = 'jdbc:mysql://172.30.81.10:9030', "connection-user" = 'root');
CREATE CATALOG

trino> SHOW CATALOGS;
 doris_local
 system

trino> SHOW SCHEMAS FROM doris_local;
 information_schema
 p0_probe
 p1_cancel
 ... (p1_*, p2_*, p3_*, p4_*, p5_* fixture schemas) ...

trino> SHOW TABLES FROM doris_local.p0_probe;
 arrays
 mapstruct
 nums
 opaque
 scalars
 scalars_view
```

### 3. SELECT with a predicate — pushed down through the manual catalog

```sql
trino> SELECT n FROM doris_local.p0_probe.nums WHERE n > 1000995 ORDER BY n;
 1000996
 1000997
 1000998
 1000999
(4 rows, out of a 1,001,000-row table)

trino> EXPLAIN SELECT n FROM doris_local.p0_probe.nums WHERE n > 1000995;
 Fragment 0 [SOURCE]
     Output[columnNames = [n]]
     └─ TableScan[table = doris_local:p0_probe.nums p0_probe.nums constraint on [n]]
            Layout: [n:bigint]
            Estimates: {rows: 4 (36B), ...}
```

**Pushdown proof:** the plan is a single `TableScan` **with `constraint on [n]`** and
**no `FilterNode`** — the `n > 1000995` predicate was pushed into the Doris JDBC scan.
The optimizer estimates **4 rows**, not the full million, i.e. the filter is applied at
the source. `EXPLAIN (TYPE IO)` reports the same `outputRowCount ≈ 4` for the table
input. This is the connector's real pushdown machinery running through a *dynamically
created, plugin-dir-loaded* catalog.

### 4. Multi-catalog

```sql
trino> CREATE CATALOG doris_local2 USING doris
    -> WITH ("connection-url" = 'jdbc:mysql://172.30.81.10:9030', "connection-user" = 'root',
    ->       "doris.query-timeout" = '300s');
CREATE CATALOG

trino> SHOW CATALOGS;
 doris_local
 doris_local2
 system

trino> SELECT count(*) FROM doris_local2.p0_probe.scalars;
 8
```

Two independent catalogs point at the same FE — proving `CREATE CATALOG` builds a
fresh connector instance each time, and the `doris.*` extra config round-trips.

### 5. DROP + teardown

```sql
trino> DROP CATALOG doris_local2;
DROP CATALOG
trino> DROP CATALOG doris_local;
DROP CATALOG
trino> SHOW CATALOGS;
 system
trino> SELECT count(*) FROM doris_local.p0_probe.nums;
Query ... failed: line 1:22: Catalog 'doris_local' not found
```

Dropped catalogs vanish immediately and dangling references fail loudly.

```sh
./down.sh   # containers + volumes removed
```
