# REPORT — multi-FE failover (trino-doris connector)

**Status: DONE.** Closes the LEDGER §F "Confirm FE failover" NOT-DONE item. A real
**3-FE (1 master + 2 followers) + 1 BE** Apache Doris `4.1.3-rc02-7126cf65d96`
cluster was stood up and the multi-host URL / failover / master-election behavior
that §F called out as "assumed, not tested" was measured against live nodes.

**Baseline:** Apache Doris `4.1.3-rc02-7126cf65d96` (FE+BE), MySQL Connector/J
`9.7.0` (BOM-aligned with Trino 483 — same driver the connector ships). All numbers
below are from the overlay described in "Topology", produced with the throwaway JDBC
probes documented in "Reproduce". This supersedes the single-FE-only PROBE §11 result
("only comma-list syntax proven, on a single FE; `sequential:(...)` rejected").

---

## Topology

Optional, manual/dev overlay cluster — files in `compose/multi-fe/`, brought up with
`compose/multi-fe/up.sh`, torn down with `compose/multi-fe/down.sh`. It is
**separate from and does not disturb** the default single-FE probe cluster
(`compose/`, project `trino-doris-dev`, host 9130/8130) that CI uses. CI never
starts the overlay.

| Node | Role (start) | Static IP     | Host MySQL port | Host HTTP port |
|------|--------------|---------------|-----------------|----------------|
| fe1  | master       | 172.30.82.10  | **9131** → 9030 | 8131 → 8030    |
| fe2  | follower     | 172.30.82.11  | **9132** → 9030 | 8132 → 8030    |
| fe3  | follower     | 172.30.82.12  | **9133** → 9030 | 8133 → 8030    |
| be1  | —            | 172.30.82.20  | —               | —              |

- Project name `trino-doris-mfe`, dedicated subnet `172.30.82.0/24` (single-FE
  cluster is on `81.x`, doris-ducklake on `80.x` — no collisions).
- **FE election wiring** (Doris image `init_fe.sh` "ELECTION" mode): every FE gets the
  same `FE_SERVERS=fe1:172.30.82.10:9010,fe2:172.30.82.11:9010,fe3:172.30.82.12:9010`
  (name:ip:**edit_log_port**) and a distinct `FE_ID`. `FE_ID=1` ⇒ master
  (`start_fe.sh --console`); `FE_ID>1` ⇒ follower (`start_fe.sh --helper
  master:9010` + self `ALTER SYSTEM ADD FOLLOWER '<ip>:9010'` against the master).
  Followers `depends_on: fe1 service_healthy`. Static IPs are mandatory: BDBJE records
  each FE's identity by `host:9010` in the replicated metadata group.
- **`SHOW FRONTENDS`** on the healthy cluster (note: Doris reports every electable FE
  as `Role=FOLLOWER`; the *elected* one carries `IsMaster=true`):

  ```
  Host          EditLogPort  QueryPort  Role      IsMaster  Alive
  172.30.82.10  9010         9030       FOLLOWER  true      true   <- initial master
  172.30.82.11  9010         9030       FOLLOWER  false     true
  172.30.82.12  9010         9030       FOLLOWER  false     true
  ```
- **Health proven:** 1 master + 2 followers all `Alive=true`, one BE
  `Alive=true`, and `SELECT 1` succeeds through **every** FE host port
  (9131/9132/9133) — followers answer FE-local reads locally and forward
  master-only operations. (`up.sh` asserts all of this before returning.)
- **Memory note (confirmed):** three FE heaps are pinned to **1g each** (not the 2g
  of the single-FE probe cluster) in `compose/multi-fe/fe.conf`, because the overlay
  co-tenants a dev box already running the single-FE cluster (2g FE) and the
  doris-ducklake cluster. 3×1g FE + BE fit; 3×2g would not. Tear the overlay down
  when finished to reclaim the memory.

---

## URL-syntax verdicts (Connector/J 9.7.0 vs Doris 4.1.3)

Matrix run against all three live FEs (probe `MfeProbe syntax`). "via FE" is the FE
that actually served the connection (identified by `SHOW FRONTENDS` →
`CurrentConnected=Yes`).

| URL form | Result | Notes |
|---|---|---|
| `jdbc:mysql://h:9131,h:9132,h:9133/` (plain comma-list) | **WORKS** | Connects to the **first** listed host while it is up (deterministic order). **Recommended production shape.** |
| `jdbc:mysql://sequential:h:9131,h:9132,h:9133/` | **WORKS** | `sequential:` **scheme prefix** on a bare comma-list is accepted. Tries hosts in listed order. |
| `jdbc:mysql://loadbalance:h:9131,h:9132,h:9133/` | **WORKS** | Load-balances new connections across the list (random/least-loaded). |
| `jdbc:mysql://failover:h:9131,h:9132,h:9133/` | **WORKS** | Explicit failover scheme; primary + sequential failover semantics. |
| `jdbc:mysql://replication:h:9131,h:9132,h:9133/` | **WORKS** | Parses; first host = "primary". Not meaningful for Doris (no RO replica routing) — do not use. |
| `jdbc:mysql://(host=h,port=9131),(host=h,port=9132),(host=h,port=9133)/` (address-equals list, no prefix) | **WORKS** | Key/value host form parses and connects. |
| `jdbc:mysql://sequential:(host=h,port=9131),(host=h,port=9132),...` (prefix **mixed with** address-equals) | **REJECTED** | `SQLNonTransientConnectionException` ← `WrongArgumentException: Failed to parse the host:port pair 'sequential:(host=…,port=…)'`. |
| `...comma-list...?failOverReadOnly=false` | **WORKS** | Plain properties ride through fine. |

**Correction to PROBE §11 / LEDGER §B multi-host row.** PROBE §11 recorded
"`sequential:(...)` form is REJECTED" and generalized to "use comma-list or a TCP LB".
The precise truth, now measured on a real multi-FE cluster:

- The rejection is **specific to mixing the `sequential:` scheme prefix with the
  address-equals `(host=…,port=…)` host form** — that exact combination fails to parse.
- The **`sequential:` / `loadbalance:` / `failover:` scheme prefixes THEMSELVES are
  valid** when applied to a **bare `host:port` comma-list**, and so is the bare
  address-equals list without any prefix.
- So the earlier blanket "sequential rejected" was an artifact of the one syntax
  tried. Both the plain comma-list and the `sequential:`-prefixed comma-list connect
  and fail over.

---

## Failover evidence

### (a) Multi-host URL — connection + queries

`jdbc:mysql://127.0.0.1:9131,127.0.0.1:9132,127.0.0.1:9133/` connects and `SELECT 1`
returns 1 (probe + the automated test `testConnectorConnectsAndQueriesThroughMultiHostUrl`,
which does the same **through the connector** via `CREATE CATALOG … USING doris`).
With all FEs up, **every** new comma-list connection lands on the **first** listed host
(fe1) — comma-list order is deterministic, not load-balanced:

```
conn#0 OK fe=172.30.82.10 connid=170
conn#1 OK fe=172.30.82.10 connid=171
… (all on .10 while it is alive)
```

### (b) New-connection failover when the connected FE is stopped

Stop the first-listed host and open fresh connections via the same comma-list URL:

```
# fe1 (.10) stopped
conn#0 FAIL CommunicationsException: Communications link failure (8339ms)
conn#1 OK   fe=172.30.82.11 connid=42 (11ms)
conn#2 OK   fe=172.30.82.11 connid=43 (10ms)
conn#3 OK   fe=172.30.82.11 connid=44 (9ms)
```

**Connector/J default (comma-list) failover semantics — measured:**

1. **New connections DO fail over** to the next live host in the list. Once the dead
   host is refusing connections (port closed), failover is **fast** — a fresh JVM
   opening a comma-list connection to a dead first host + live others succeeds in
   ~10–234 ms (`FirstConnFailover` variants A–E all OK). The driver blacklists the
   dead host for subsequent attempts.
2. **The very first attempt during the transient right after a `docker stop` can
   still throw** `CommunicationsException` after ~8 s. Cause: the docker-proxy briefly
   keeps the mapped host port open and **blackholes** the SYN (no RST), so the driver
   waits out `connectTimeout` (and, because the socket is accepted then dropped, longer
   than the nominal 3 s) before trying the next host. Once the port is fully closed,
   connections are **refused immediately** and failover is transparent. **Operational
   implication:** set a **finite, small `connectTimeout`** and make callers
   **retry once** — the connector's Base-JDBC pooling opens connections lazily and a
   retry lands on a survivor. (This is exactly what the automated test's
   `awaitMultiHostSelectOne` models.)
3. All prefix forms (`sequential:`/`loadbalance:`/`failover:`) fail over too; only the
   *host-selection policy* differs (ordered vs. balanced). No prefix is *required* for
   failover — the plain comma-list already fails over.

### (c) In-flight query behavior on FE death (reconnect-on-next-query)

Open a connection to one FE, start `SELECT SLEEP(25)`, SIGKILL that FE mid-query:

```
READY connid=9 url=jdbc:mysql://127.0.0.1:9133/
# fe3 SIGKILLed ~5s into the sleep
INFLIGHT-FAILED CommunicationsException: Communications link failure ms=4802
NEW-CONN-AFTER-DEATH OK =3 ms=107
```

- **The in-flight query FAILS** the instant its FE dies (`CommunicationsException`).
  There is **no transparent mid-query failover** — a query bound to a dead FE is lost.
- **Reconnect-on-next-query is proven:** a brand-new connection via the multi-host URL
  succeeds immediately (107 ms) on a surviving FE. This is the claim §F needed: the
  connector recovers on the *next* statement/connection, not the in-flight one.

### (d) Master FE death — re-election + read availability (timed)

Two death modes, very different timings:

| Death mode | Command | New master elected in | Reads through a survivor during the gap |
|---|---|---|---|
| **Graceful stop** (SIGTERM) | `docker stop <master>` | **~2.4 s** (during the graceful-shutdown window — BDBJE hands off before the process exits) | Available almost immediately after stop returns (~6 ms poll) |
| **Hard crash** (SIGKILL) | `docker kill <master>` | **~63 s** | FE-local trivial reads (`SELECT 1`) keep working on survivors throughout; **real metadata/BE queries (e.g. `information_schema.tables`) are UNAVAILABLE for the full ~63 s** until a new master is elected, then succeed |

Observed election hand-offs (2-of-3 quorum retained each time, `ClusterId` unchanged):
`.10 → .12` (kill .10), `.12 → .11` (kill .12), `.11 → .12` (kill .11). After each,
`SHOW FRONTENDS` shows the killed node `Alive=false` and a survivor `IsMaster=true`;
restarting the killed container rejoins it as a follower (its edit-log catches up).

Outage-window sampling on a hard master crash:

```
t=0.0s  select1=1  dataquery=X   master=NONE     <- FE up for trivial reads; no master
t=65.3s select1=1  dataquery=60  master=172.30.82.11  <- new master; real queries resume
```

**The ~63 s hard-crash gap is the BDBJE master heartbeat/quorum timeout**, not a
connector or driver property. It is the single most important production caveat: a
*crashed* master (kernel panic, OOM-kill, `kill -9`, power loss) leaves the cluster
**unable to serve real queries for ~1 minute** even though the surviving FEs' MySQL
ports stay open and answer `SELECT 1`. A *graceful* FE stop (rolling restart, planned
maintenance, `docker stop`, k8s SIGTERM) hands off in seconds. Tune Doris FE BDBJE
election/heartbeat settings if a shorter crash-recovery window is required (out of
scope for this evidence run).

---

## Recommended production URL shape + caveats

**Recommended:** put a **TCP load balancer / VIP in front of the FE MySQL ports** and
give the connector a single stable endpoint —

```
connection-url = jdbc:mysql://doris-fe-vip:9030/
```

The LB owns health-checking and steering away from dead/electing FEs, so the
connector never sees the ~8 s docker-proxy-style transient or the comma-list
"first-attempt-to-a-dead-host" edge, and you can add/remove FEs without editing the
catalog. This is the shape to prefer for any real deployment.

**Acceptable without an LB:** the plain comma-list —

```
connection-url = jdbc:mysql://fe1:9030,fe2:9030,fe3:9030/
```

with these caveats baked into config/runbook:

- Set a **finite, small `connectTimeout`** (e.g. `2000`) and a finite `socketTimeout`;
  the driver only moves to the next host after the current one's connect times out.
- Expect the **first connection after a sudden FE death** to possibly fail once
  (the transient window); ensure the calling layer **retries** (Base-JDBC's lazy
  pooling means the retry opens a new connection that lands on a survivor).
- The list is **static** — adding/removing an FE means editing every catalog's URL.
- Do **not** append a database/catalog to the URL (the connector's
  `isUrlWithoutDatabase` guard rejects it — LEDGER §B).
- `sequential:` prefix is optional and equivalent to the bare comma-list for ordered
  failover; `loadbalance:` spreads new connections. Neither is required.

**What the connector does / does NOT guarantee (given a multi-host or LB URL):**

- **DOES:** new connections/queries reach a surviving FE (driver- or LB-level
  failover); reads keep working through followers while a master is alive; recovery on
  the next statement after an FE death (reconnect-on-next-query).
- **DOES NOT:** save an **in-flight** query whose FE dies — it fails and must be
  retried by the caller/engine. Trino will fail the affected split/query; a retry
  re-plans against a live FE.
- **DOES NOT:** shorten the **~60 s hard-crash master re-election** window — during it,
  real queries fail cluster-wide regardless of URL shape. Plan SLAs around it or tune
  Doris BDBJE. Graceful FE restarts do not incur it.

---

## Automated test + guard mechanism

`test/src/dev/brikk/trino/doris/TestDorisMultiFeFailover.kt` (self-contained; no shared
utility modified):

- **Guard:** `@EnabledIf("dev.brikk.trino.doris.TestDorisMultiFeFailover#overlayIsUp")`.
  `overlayIsUp()` TCP-probes all three overlay ports (9131/9132/9133, 750 ms each). If
  any is unreachable the **whole class is DISABLED and skipped cleanly** — so a default
  `./gradlew build` (overlay not started, the CI norm) is never broken by this suite.
- **When the overlay is up it asserts:**
  1. `testConnectorConnectsAndQueriesThroughMultiHostUrl` — the connector connects +
     queries through the comma-list multi-host URL (`CREATE CATALOG doris_mfe USING
     doris WITH (connection-url='jdbc:mysql://…9131,…9132,…9133/')`, then `SELECT 1` +
     `SHOW SCHEMAS`).
  2. `testNewConnectionFailoverAfterFollowerStops` — stops a **non-master follower**
     (chosen by reading `SHOW FRONTENDS` for an alive, `IsMaster=false` node and
     mapping its IP → container), then proves NEW connections (raw Connector/J **and**
     through the connector) still succeed on the surviving FEs; `@AfterEach` restarts
     the follower and waits for it to rejoin, leaving the overlay intact.
- The **destructive master-kill / ~63 s re-election** evidence is intentionally **not**
  automated (slow + perturbs the shared overlay); it is the scripted procedure below.
- **Result with overlay up:** `tests=3 skipped=0 failures=0 errors=0` (2 real tests +
  the framework naming check). With overlay down: skipped, build green.

---

## Reproduce

Throwaway probes (JDK + Connector/J 9.7.0 on the classpath) used for the evidence
above are not checked in (they are scratch); the exact procedure:

```sh
# 1. Bring up the overlay (leaves it healthy; ~3-5 min for followers to join)
compose/multi-fe/up.sh

# 2. Syntax matrix + which-FE reporting  (MfeProbe.java)
java -cp .:mysql-connector-j-9.7.0.jar MfeProbe syntax

# 3. New-connection failover (stop a host, open fresh comma-list conns) (MfeFailover.java)
docker kill trino-doris-mfe-fe1
java -cp .:mysql-connector-j-9.7.0.jar MfeFailover newconn-loop \
    "jdbc:mysql://127.0.0.1:9131,127.0.0.1:9132,127.0.0.1:9133/" 5
docker start trino-doris-mfe-fe1

# 4. In-flight query death + reconnect-on-next-query (InflightProbe.java)
java -cp .:mysql-connector-j-9.7.0.jar InflightProbe \
    "jdbc:mysql://127.0.0.1:9133/" \
    "jdbc:mysql://127.0.0.1:9131,127.0.0.1:9132,127.0.0.1:9133/" &
sleep 5; docker kill trino-doris-mfe-fe3; wait; docker start trino-doris-mfe-fe3

# 5. Timed master crash / re-election (SIGKILL the current master, poll for a new one)
#    - discover master via a survivor: SHOW FRONTENDS\G | IsMaster: true
#    - docker kill <master>; poll survivor SHOW FRONTENDS for a new IsMaster + first
#      successful `SELECT COUNT(*) FROM information_schema.tables`
#    Graceful variant: docker stop <master> (hands off in ~2.4s).

# 6. The guarded automated test
./gradlew test --tests dev.brikk.trino.doris.TestDorisMultiFeFailover

# 7. Tear the overlay down (frees memory)
compose/multi-fe/down.sh
```

---

*End of report. Closes LEDGER §F "Confirm FE failover".*
