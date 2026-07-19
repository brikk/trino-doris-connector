#!/usr/bin/env bash
# Bring up the OPTIONAL trino-doris multi-FE FAILOVER OVERLAY cluster.
#
#   THREE FEs (fe1 master + fe2/fe3 followers) + ONE BE, project trino-doris-mfe,
#   dedicated subnet 172.30.82.0/24, host MySQL ports 9131/9132/9133 -> the three
#   FEs' 9030. This is a MANUAL / DEV overlay used to produce the failover
#   evidence in dev-docs/REPORT-multi-fe-failover.md. It does NOT replace or
#   disturb the default single-FE probe cluster (../up.sh, project
#   trino-doris-dev, host 9130). CI uses the single-FE cluster only.
#
# Usage:
#   ./up.sh            # stage runtime confs, up 3 FEs + BE, wait for HA + BE alive
#   ./up.sh --down     # tear everything down (containers + volumes)
#
# Memory: 3x 1g FE heaps + BE. This overlay is co-tenant with the single-FE
# cluster and doris-ducklake; TEAR IT DOWN with ./down.sh when finished.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE="${HERE}/docker-compose.yml"

# All FE MySQL host ports, in FE_ID order (fe1, fe2, fe3).
FE_PORTS=(9131 9132 9133)
FE_CONTAINERS=(trino-doris-mfe-fe1 trino-doris-mfe-fe2 trino-doris-mfe-fe3)

log() { printf '\033[1;35m[trino-doris-mfe-up]\033[0m %s\n' "$*"; }

stage_runtime_confs() {
    # init_fe.sh appends `priority_networks` to the mounted fe.conf on first init;
    # init_be.sh does the same to be.conf. Mount gitignored PER-NODE runtime copies
    # (never the tracked templates) so those appends never pollute the repo. Staged
    # fresh from the pristine tracked multi-fe/fe.conf / be.conf on every run.
    cp "${HERE}/fe.conf" "${HERE}/.fe1.conf.runtime"
    cp "${HERE}/fe.conf" "${HERE}/.fe2.conf.runtime"
    cp "${HERE}/fe.conf" "${HERE}/.fe3.conf.runtime"
    cp "${HERE}/be.conf" "${HERE}/.be.conf.runtime"
}

if [[ "${1:-}" == "--down" ]]; then
    log "Tearing down trino-doris-mfe overlay (containers + volumes)…"
    # Stage the runtime confs so `down` finds the mount sources present.
    stage_runtime_confs
    docker compose -f "${COMPOSE}" down -v
    exit 0
fi

stage_runtime_confs

log "Starting multi-FE overlay: fe1(master) + fe2/fe3(followers) + BE (project trino-doris-mfe)…"
log "Followers join after fe1 is healthy (depends_on: service_healthy); this can take a few minutes."
docker compose -f "${COMPOSE}" up -d

# Wait for every FE to accept SQL on its own host port.
for i in "${!FE_PORTS[@]}"; do
    port="${FE_PORTS[$i]}"
    container="${FE_CONTAINERS[$i]}"
    log "Waiting for ${container} to accept SQL (SELECT 1 on host ${port})…"
    deadline=$((SECONDS + 300))
    while :; do
        if docker exec "${container}" mysql -h127.0.0.1 -P9030 -uroot -e "SELECT 1" >/dev/null 2>&1; then
            log "${container} up."
            break
        fi
        if (( SECONDS >= deadline )); then
            log "${container} never came up — last 80 lines of its fe.log:"
            docker exec "${container}" sh -c 'tail -80 /opt/apache-doris/fe/log/fe.log' 2>&1 || true
            exit 1
        fi
        sleep 3
    done
done

# Wait for the HA group to converge: 1 master + 2 followers, all Alive=true.
log "Waiting for the FE HA group to converge (1 master + 2 followers, all Alive)…"
deadline=$((SECONDS + 300))
while :; do
    # SHOW FRONTENDS columns include Role, IsMaster, Alive. Count alive + masters.
    frontends=$(docker exec trino-doris-mfe-fe1 mysql -h127.0.0.1 -P9030 -uroot -N -e "SHOW FRONTENDS" 2>/dev/null || true)
    alive=$(printf '%s\n' "${frontends}" | grep -c -w "true" || true)
    total=$(printf '%s\n' "${frontends}" | grep -c -w "FOLLOWER" || true)
    # A converged 3-follower group: 3 FOLLOWER rows, and each has Alive=true (plus one IsMaster=true).
    masters=$(printf '%s\n' "${frontends}" | awk -F'\t' '{for(i=1;i<=NF;i++) if($i=="true") c++} END{print c+0}')
    if [[ "${total:-0}" -ge 3 ]]; then
        # Confirm all three are alive via a per-row alive check.
        alive_rows=$(printf '%s\n' "${frontends}" | awk -F'\t' 'BEGIN{n=0} /FOLLOWER/{for(i=1;i<=NF;i++) if($i=="true") a=1; if(a){n++}; a=0} END{print n}')
        if [[ "${alive_rows:-0}" -ge 3 ]]; then
            log "HA group converged: 3 followers present and alive."
            break
        fi
    fi
    if (( SECONDS >= deadline )); then
        log "HA group did NOT converge in time — SHOW FRONTENDS\\G:"
        docker exec trino-doris-mfe-fe1 mysql -h127.0.0.1 -P9030 -uroot -e "SHOW FRONTENDS\G" 2>&1 || true
        exit 1
    fi
    sleep 3
done

log "Waiting for BE registration (SHOW BACKENDS → Alive=true)…"
deadline=$((SECONDS + 300))
while :; do
    alive=$(docker exec trino-doris-mfe-fe1 mysql -h127.0.0.1 -P9030 -uroot -N -e "SHOW BACKENDS" 2>/dev/null | awk -F'\t' '{for(i=1;i<=NF;i++) if($i=="true"){print "1"; exit}}')
    if [[ "${alive:-0}" == "1" ]]; then
        log "BE registered and alive."
        break
    fi
    if (( SECONDS >= deadline )); then
        log "BE failed to register — SHOW BACKENDS\\G:"
        docker exec trino-doris-mfe-fe1 mysql -h127.0.0.1 -P9030 -uroot -e "SHOW BACKENDS\G" 2>&1 || true
        exit 1
    fi
    sleep 3
done

log "Health + sanity gates (SHOW FRONTENDS / BACKENDS + SELECT through every FE port):"
docker exec trino-doris-mfe-fe1 mysql -h127.0.0.1 -P9030 -uroot -e "
    SELECT VERSION() AS fe_version;
    SHOW FRONTENDS\G
    SHOW BACKENDS\G
" 2>&1 | tail -80

for port in "${FE_PORTS[@]}"; do
    # Prove SELECT works through each FE port from the HOST (mysql client via the mapped port).
    result=$(docker run --rm --network host mysql:8.4 mysql -h127.0.0.1 -P"${port}" -uroot -N -e "SELECT 1" 2>/dev/null || echo "FAILED")
    log "SELECT 1 through host port ${port}: ${result}"
done

log "Overlay is UP."
log "FE MySQL host ports: 9131 (fe1/master), 9132 (fe2), 9133 (fe3). HTTP: 8131/8132/8133."
log "Multi-host failover URL: jdbc:mysql://127.0.0.1:9131,127.0.0.1:9132,127.0.0.1:9133/"
log "Tear down (frees memory) with: ./down.sh"
