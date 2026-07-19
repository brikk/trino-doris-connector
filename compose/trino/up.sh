#!/usr/bin/env bash
#
# Bring up the MANUAL trino-doris smoke sandbox: a single Trino 483 coordinator
# with OUR plugin installed (production-shaped plugin-dir ServiceLoader load) and
# dynamic catalog management enabled. Then print a copy-paste cheat sheet.
#
# Usage:
#   ./up.sh              # assemble plugin if missing, up Trino, wait healthy, print cheat sheet
#   ./up.sh --rebuild    # force `./gradlew pluginAssemble` even if the dir exists
#   ./down.sh            # tear down (alias for ./up.sh --down)
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${HERE}/../.." && pwd)"
COMPOSE="${HERE}/docker-compose.yml"
CONTAINER="trino-doris-manual"

# Local compose Doris target (container-internal address on trino-doris-dev_doris-net).
LOCAL_DORIS_URL="jdbc:mysql://172.30.81.10:9030"

log() { printf '\033[1;36m[trino-doris-manual]\033[0m %s\n' "$*"; }
err() { printf '\033[1;31m[trino-doris-manual]\033[0m %s\n' "$*" >&2; }

REBUILD=0
case "${1:-}" in
    --down)
        log "Tearing down the manual sandbox…"
        # Plugin dir must resolve for compose to parse the file; point it anywhere real.
        TRINO_DORIS_PLUGIN_DIR="${HERE}/etc" docker compose -f "${COMPOSE}" down -v || true
        exit 0
        ;;
    --rebuild) REBUILD=1 ;;
    "") ;;
    *) err "unknown arg: ${1}"; exit 2 ;;
esac

# --- Locate the assembled plugin dir (build/trino-plugin/trino-doris-<version>) ----
# Resolve <version> from the gradle build the same way the build does. We avoid a
# gradle invocation just to read the version: match the single trino-doris-* dir.
find_plugin_dir() {
    local base="${REPO_ROOT}/build/trino-plugin"
    [[ -d "${base}" ]] || return 1
    local d
    d="$(find "${base}" -maxdepth 1 -type d -name 'trino-doris-*' | head -n1)"
    [[ -n "${d}" ]] || return 1
    # Must contain the SPI-registered plugin jar to be a real assembly.
    [[ -n "$(find "${d}" -maxdepth 1 -name 'trino-doris-connector-*.jar' | head -n1)" ]] || return 1
    printf '%s' "${d}"
}

assemble_plugin() {
    log "Assembling plugin via ./gradlew pluginAssemble (mise JDK 25)…"
    export JAVA_HOME="${HOME}/.local/share/mise/installs/java/25"
    [[ -x "${JAVA_HOME}/bin/java" ]] || { err "mise JDK 25 not found at ${JAVA_HOME}"; exit 1; }
    ( cd "${REPO_ROOT}" && ./gradlew pluginAssemble )
}

PLUGIN_DIR="$(find_plugin_dir || true)"
if [[ -z "${PLUGIN_DIR}" || "${REBUILD}" -eq 1 ]]; then
    [[ "${REBUILD}" -eq 1 ]] && log "--rebuild requested." || log "Plugin dir not found."
    assemble_plugin
    PLUGIN_DIR="$(find_plugin_dir)" || { err "pluginAssemble did not produce a plugin dir"; exit 1; }
fi
log "Plugin dir: ${PLUGIN_DIR}"
log "  ServiceLoader SPI: $(unzip -p "$(find "${PLUGIN_DIR}" -name 'trino-doris-connector-*.jar' | head -n1)" META-INF/services/io.trino.spi.Plugin 2>/dev/null || echo '??')"
export TRINO_DORIS_PLUGIN_DIR="${PLUGIN_DIR}"

# --- Up --------------------------------------------------------------------------
if ! docker network inspect trino-doris-dev_doris-net >/dev/null 2>&1; then
    err "network trino-doris-dev_doris-net not found — the local compose Doris cluster is not up."
    err "Either start it (../up.sh) for a zero-config first smoke, or edit docker-compose.yml"
    err "to drop the 'doris-net' attachment and use 'docker network connect' later."
    exit 1
fi

log "Starting Trino 483 with the plugin dir mounted read-only…"
docker compose -f "${COMPOSE}" up -d

# --- Wait for health -------------------------------------------------------------
log "Waiting for Trino to answer SELECT 1…"
deadline=$((SECONDS + 180))
while :; do
    if docker exec "${CONTAINER}" trino --execute 'SELECT 1' >/dev/null 2>&1; then
        log "Trino is up."
        break
    fi
    if (( SECONDS >= deadline )); then
        err "Trino never became healthy — last 60 lines of server log:"
        docker logs --tail 60 "${CONTAINER}" 2>&1 || true
        exit 1
    fi
    sleep 3
done

# Confirm OUR plugin loaded from the plugin dir (production-shaped ServiceLoader path).
log "Confirming the doris connector registered from the plugin dir…"
if docker logs "${CONTAINER}" 2>&1 | grep -q "Registering connector doris"; then
    log "  ✓ 'Registering connector doris' — plugin loaded via /usr/lib/trino/plugin ServiceLoader."
else
    log "  (connector-registration log line not matched; CREATE CATALOG below is the real proof)"
fi

# --- Cheat sheet -----------------------------------------------------------------
cat <<EOF

$(printf '\033[1;32m')════════════════════════════════════════════════════════════════════════════
  trino-doris MANUAL sandbox is UP  →  http://localhost:18080  (CLI below)
════════════════════════════════════════════════════════════════════════════$(printf '\033[0m')

  # 1. Get a CLI inside the container:
  docker exec -it ${CONTAINER} trino

  # 2a. Add the LOCAL bundled Doris cluster (zero-config, for a first smoke):
  CREATE CATALOG doris_local USING doris
  WITH (
      "connection-url"  = '${LOCAL_DORIS_URL}',
      "connection-user" = 'root'
  );
  --   (root, empty password — the bundled dev cluster has no password.)

  # 2b. Add YOUR OWN external Doris server (edit host/creds). Use
  #     host.docker.internal for a Doris on THIS machine, or the LAN IP:
  CREATE CATALOG doris_prod USING doris
  WITH (
      "connection-url"      = 'jdbc:mysql://host.docker.internal:9030',
      "connection-user"     = 'trino_ro',
      "connection-password" = 'CHANGE_ME'
  );
  --   Recommended: give Trino a SELECT-only account on your Doris FE first:
  --     DROP USER IF EXISTS 'trino_ro'@'%';
  --     CREATE USER 'trino_ro'@'%' IDENTIFIED BY 'CHANGE_ME';
  --     GRANT SELECT_PRIV ON internal.*.* TO 'trino_ro'@'%';

  # 3. Sample queries:
  SHOW CATALOGS;
  SHOW SCHEMAS FROM doris_local;
  SHOW TABLES FROM doris_local.p0_probe;
  SELECT n FROM doris_local.p0_probe.nums WHERE n > 1000995;
  EXPLAIN SELECT n FROM doris_local.p0_probe.nums WHERE n > 1000995;   -- see predicate pushdown

  # 4. Remove a catalog when done:
  DROP CATALOG doris_local;

  # 5. Tear the whole sandbox down:
  ${HERE}/down.sh

$(printf '\033[1;32m')════════════════════════════════════════════════════════════════════════════$(printf '\033[0m')
EOF
