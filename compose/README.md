# trino-doris probe cluster (stock Apache Doris 4.1.3)

Isolated, evidence-grade stock Doris 4.1.3 cluster used by the P0 protocol/type
probe (`../dev-docs/REPORT-protocol-type-probe-doris-4.1.3.md`).

## Why a separate cluster

`jvm/doris-ducklake` runs a **patched, master-based FE** (`doris-fe:pr62767-local`)
on network `172.30.80.0/24` and host ports `9030`/`8030`. That FE is not a stock
4.1.3 build, so it cannot be used for protocol/type evidence, and it must not be
disturbed.

This cluster runs the **stock `apache/doris:fe-4.1.3` + `be-4.1.3` images** on a
dedicated `172.30.81.0/24` network with host ports:

| Purpose          | Host port | Container port |
|------------------|-----------|----------------|
| FE MySQL proto   | **9130**  | 9030           |
| FE HTTP          | **8130**  | 8030           |

Both clusters can run simultaneously without colliding.

## Usage

```sh
./up.sh          # bring up FE+BE, wait for SELECT 1 and SHOW BACKENDS Alive=true
./up.sh --down   # tear down (containers + volumes)
./down.sh        # alias for ./up.sh --down
```

Connect from the host:

```sh
mysql -h127.0.0.1 -P9130 -uroot     # no password
```

JDBC: `jdbc:mysql://127.0.0.1:9130/` (user `root`, empty password).

## SELECT-only fixture account (`trino_ro`)

The P1 read-only suites (`TestDorisTrinoRoAccount`) provision a SELECT-only account so
Doris itself denies writes even if connector code regressed (PLAN G7.1 — the stock-connector
DELETE incident proved code-only enforcement is insufficient). The test setup recreates it
idempotently; to create it by hand:

```sql
DROP USER IF EXISTS 'trino_ro'@'%';
CREATE USER 'trino_ro'@'%' IDENTIFIED BY '';
GRANT SELECT_PRIV ON internal.*.* TO 'trino_ro'@'%';
```

Proven on 4.1.3: `SELECT`/`SHOW` work; `INSERT`/`DELETE`/`UPDATE` fail with
`LOAD command denied`, DDL with `Access denied ... (CREATE) privilege(s)`.

## Fixture databases

`p0_probe` is provisioned AUTOMATICALLY by the test harness (`DorisFixtures`, wired into
every query-runner build): a fresh cluster gets the full byte-exact replica of the original
P0 evidence fixtures (scalars/arrays/mapstruct/opaque/scalars_view + the 1M-row `nums`,
seeded in ~250ms via the `numbers()` TVF) on first suite run; complete fixtures are detected
by row-count fingerprint and skipped. Tests never mutate it. The suites own and recreate
`p1_smoke`, `p1_readonly`, `p1_ro_smoke`, `p1_cancel` (`p1_cancel.big` is ~500 MB on the
wire but near-zero on disk), `p2_*`, `p3_*`, `p4_*`, and `p5_batch`. **No manual fixture
step exists anymore** — `./up.sh` + `./gradlew build` from zero is the supported path (this
is what CI does).

## Multi-FE failover overlay (`multi-fe/`, OPTIONAL, manual/dev only)

`multi-fe/` is a **separate, optional** overlay cluster used to produce the multi-FE
failover evidence in `../dev-docs/REPORT-multi-fe-failover.md`. It is **NOT** part of
CI and does **NOT** replace or disturb the default single-FE cluster above — CI keeps
using the single-FE cluster (host 9130). Bring the overlay up by hand only when you
want to re-run / extend the failover evidence, and **tear it down when finished** (it
runs three FEs + a BE and is memory-hungry).

- **Topology:** three FEs (`fe1` initial master + `fe2`/`fe3` followers that join the
  BDBJE electable group via `--helper`) and one BE, project `trino-doris-mfe`, on a
  dedicated `172.30.82.0/24` network (no collision with `81.x`/`80.x`).

  | Node | Host MySQL port | Host HTTP port |
  |------|-----------------|----------------|
  | fe1 (master) | **9131** → 9030 | 8131 → 8030 |
  | fe2 (follower) | **9132** → 9030 | 8132 → 8030 |
  | fe3 (follower) | **9133** → 9030 | 8133 → 8030 |

- **Usage:**

  ```sh
  ./multi-fe/up.sh      # up 3 FEs + BE, wait for HA convergence + BE alive
  ./multi-fe/down.sh    # tear down (containers + volumes) — frees the memory
  ```

- **Multi-host failover URL:** `jdbc:mysql://127.0.0.1:9131,127.0.0.1:9132,127.0.0.1:9133/`
  (plain comma-list). Verdicts, timings, and the production-URL recommendation (TCP
  LB/VIP preferred) are in `../dev-docs/REPORT-multi-fe-failover.md`.

- **Guarded test:** `TestDorisMultiFeFailover` is `@EnabledIf`-guarded on all three
  overlay ports being reachable, so it runs when the overlay is up and **skips cleanly**
  (build stays green) when it is not.

- **Lessons reused from the single-FE cluster:** `priority_networks` pinned per node,
  gitignored per-node runtime confs staged by `up.sh` (init scripts append
  `priority_networks`), static IPs (BDBJE identity), `nofile` 65536, amd64 BE. The one
  deliberate difference: **FE heap is 1g** (not 2g) because three FEs must co-tenant the
  dev box alongside the single-FE and doris-ducklake clusters.

## Lessons baked in

- **`priority_networks = 172.30.81.0/24`** is written into both `fe.conf` and
  `be.conf` so FE/BE restarts never bind the wrong host interface.
- **FE heap dropped to 2g** via `JAVA_OPTS_FOR_JDK_17` in `fe.conf` (the stock 8g
  default does not fit a typical dev-box memory ceiling; `start_fe.sh` sources
  `fe.conf` for JAVA_OPTS, so an env-var override does not work).
- **`.fe.conf.runtime` is gitignored** and staged fresh from the tracked pristine
  `fe.conf` by `up.sh` on every run, because Doris's `init_fe.sh` appends a
  `priority_networks` line to the mounted `fe.conf` at every boot.
- `FE_SERVERS=fe1:172.30.81.10:9010`, `BE_ADDR=172.30.81.20:9050`, BE `nofile`
  ulimit 65536.
- BE platform pinned `linux/amd64` (x86_64 host).

No ducklake plugin, no substrate (Postgres/MinIO) networks, no corpus mounts.
