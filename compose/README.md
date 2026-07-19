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

`p0_probe` / `p0_array_spike` / `p0_*` are P0 evidence fixtures — never mutated by tests.
The P1 suites own and recreate `p1_smoke`, `p1_readonly`, `p1_ro_smoke`, and `p1_cancel`
(`p1_cancel.big` is ~500 MB on the wire but near-zero on disk; kept across runs).

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
