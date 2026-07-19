#!/usr/bin/env bash
# Bring up the ISOLATED stock Doris 4.1.3 cluster for the trino-doris P0 probe.
#
# Usage:
#   ./up.sh            # stage runtime fe.conf, up FE+BE, wait for SELECT 1 + BE alive
#   ./up.sh --down     # tear everything down (containers + volumes)
#
# The FE is reachable on the HOST at 127.0.0.1:9130 (mysql protocol, user root,
# no password). HTTP is on 127.0.0.1:8130. This is separate from the
# doris-ducklake cluster (9030/8030) so both can run at once.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE="${HERE}/docker-compose.yml"

log() { printf '\033[1;36m[trino-doris-up]\033[0m %s\n' "$*"; }

if [[ "${1:-}" == "--down" ]]; then
    log "Tearing down trino-doris-dev stack (containers + volumes)…"
    # Stage the runtime confs so `down` finds the mount sources present.
    cp "${HERE}/fe.conf" "${HERE}/.fe.conf.runtime"
    cp "${HERE}/be.conf" "${HERE}/.be.conf.runtime"
    docker compose -f "${COMPOSE}" down -v
    exit 0
fi

# init_fe.sh appends `priority_networks` to fe.conf at every boot. Mount a
# gitignored runtime copy (not the tracked file) so those appends never pollute
# the repo. Stage it fresh from the pristine tracked fe.conf.
cp "${HERE}/fe.conf" "${HERE}/.fe.conf.runtime"
# init_be.sh likewise appends `priority_networks` to be.conf on first init; stage
# a writable runtime copy so that append succeeds (a read-only be.conf mount makes
# the BE die before it can even open its log).
cp "${HERE}/be.conf" "${HERE}/.be.conf.runtime"

log "Starting stock Doris 4.1.3 FE + BE (project trino-doris-dev)…"
docker compose -f "${COMPOSE}" up -d

log "Waiting for FE to accept SQL (SELECT 1 on 9030)…"
deadline=$((SECONDS + 240))
while :; do
    if docker exec trino-doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "SELECT 1" >/dev/null 2>&1; then
        log "FE up."
        break
    fi
    if (( SECONDS >= deadline )); then
        log "FE never came up — last 80 lines of fe.log:"
        docker exec trino-doris-fe sh -c 'tail -80 /opt/apache-doris/fe/log/fe.log' 2>&1 || true
        exit 1
    fi
    sleep 2
done

log "Waiting for BE registration (SHOW BACKENDS → Alive=true)…"
deadline=$((SECONDS + 240))
while :; do
    alive=$(docker exec trino-doris-fe mysql -h127.0.0.1 -P9030 -uroot -N -e "SHOW BACKENDS" 2>/dev/null | awk -F'\t' '$10=="true"{n++} END{print n+0}')
    if [[ "${alive:-0}" -ge 1 ]]; then
        log "BE registered and alive."
        break
    fi
    if (( SECONDS >= deadline )); then
        log "BE failed to register — SHOW BACKENDS output:"
        docker exec trino-doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "SHOW BACKENDS\G" 2>&1 || true
        exit 1
    fi
    sleep 3
done

log "Version + sanity gates:"
docker exec trino-doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "
    SELECT VERSION() AS fe_version;
    SHOW FRONTENDS\G
    SHOW BACKENDS\G
    SELECT 1 AS wire_compat_ok;
" 2>&1 | tail -60

log "Cluster is up. FE mysql: 127.0.0.1:9130 (root, no password) | HTTP: 127.0.0.1:8130"
log "Tear down with: ./up.sh --down"
