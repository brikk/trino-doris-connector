# Local Trino sandbox

This starts a Trino **483** coordinator with the verified current Doris plugin,
dynamic catalogs and an in-memory catalog store. It is for local evaluation,
not a secure shared service.

## Start and connect

From the repository root, with Docker and JDK 25 available:

```sh
./compose/up.sh
./compose/trino/up.sh
docker exec -it trino-doris-manual trino
```

`up.sh` assembles/verifies the current plugin, uses its exact Gradle-written path,
and mounts an immutable snapshot read-only. `--rebuild` reruns build tasks.
The local `trino-doris-dev_doris-net` network is required by the checked-in
Compose file and startup script, even when the catalog will use an external FE.

Inside the CLI, connect to the local bundled FE:

```sql
CREATE CATALOG doris_local USING doris
WITH (
  "connection-url" = 'jdbc:mysql://172.30.81.10:9030',
  "connection-user" = 'root'
);
SHOW SCHEMAS FROM doris_local;
DROP CATALOG doris_local;
```

For another server, use a SELECT-only account and the catalog settings in the
[connector README](../../README.md). `host.docker.internal` reaches the Docker
host; an FE inside another network needs suitable container routing. Host-port
publication does not change the FE address reachable from inside Trino.

## Access and safety

HTTP/CLI is **127.0.0.1:18080**, with no authentication. Any reachable user can
query its catalogs and create new ones. Do not add production credentials to a
shared sandbox. Local users and Docker peers are not isolated by loopback binding.

Prefer an SSH tunnel for remote access. `TRINO_BIND_ADDRESS=<specific-host-IP>`
is an explicit sharing override for an isolated trusted network; `0.0.0.0` exposes
all IPv4 interfaces. A secure deployment needs separately configured TLS/authentication.

Same-host CLI: `trino --server http://127.0.0.1:18080 --user local`.
For a no-auth JDBC client, use `jdbc:trino://127.0.0.1:18080?sessionUser=local`
without user/password credential fields; the driver's credential path requires TLS.

Catalogs disappear when the container is recreated. Test-harness mutation/failover
permissions are not required to run this read-only connector sandbox.

## Stop

```sh
./compose/trino/down.sh
docker compose -f compose/docker-compose.yml stop
```

The second command preserves local Doris volumes. Doris `compose/down.sh` or
`compose/up.sh --down` removes that stack's volumes. Developer integration-test
procedures are in [Development](../../dev-docs/DEVELOPMENT.md).
