#!/usr/bin/env bash
#
# Bring up the MANUAL trino-doris smoke sandbox: a single Trino 484 coordinator
# with OUR plugin installed (production-shaped plugin-dir ServiceLoader load) and
# dynamic catalog management enabled. Then print a copy-paste cheat sheet.
#
# Usage:
#   ./up.sh              # assemble/verify current plugin, up Trino, wait healthy, print cheat sheet
#   ./up.sh --rebuild    # additionally rerun build tasks instead of using up-to-date output
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

# --- Assemble/verify, then consume the exact path written by that Gradle run ----
# Always invoke verification, even with an existing assembly. Gradle's normal
# up-to-date checks keep this cheap. Old versioned directories are never searched.
log "Assembling and verifying the current plugin via ./gradlew prepareManualPlugin…"
export JAVA_HOME="${JAVA_HOME:-${HOME}/.local/share/mise/installs/java/25}"
[[ -x "${JAVA_HOME}/bin/java" ]] || { err "JDK 25 not found at ${JAVA_HOME}"; exit 1; }
GRADLE_ARGS=(prepareManualPlugin --console=plain)
[[ "${REBUILD}" -eq 0 ]] || GRADLE_ARGS+=(--rerun-tasks)
( cd "${REPO_ROOT}" && ./gradlew "${GRADLE_ARGS[@]}" )
IFS= read -r PLUGIN_DIR < "${REPO_ROOT}/build/manual-plugin-path.txt"
[[ -d "${PLUGIN_DIR}" ]] || { err "verified plugin directory is missing: ${PLUGIN_DIR}"; exit 1; }
log "Plugin dir: ${PLUGIN_DIR}"
CONNECTOR_JARS=("${PLUGIN_DIR}"/trino-doris-connector-*.jar)
[[ "${#CONNECTOR_JARS[@]}" -eq 1 && -f "${CONNECTOR_JARS[0]}" ]] || { err "ambiguous connector JAR"; exit 1; }
log "  ServiceLoader SPI: $(unzip -p "${CONNECTOR_JARS[0]}" META-INF/services/io.trino.spi.Plugin)"

# SNAPSHOT the plugin dir instead of mounting Gradle's live output: a later
# `./gradlew build` rewrites those jars underneath the running JVM and the server
# starts throwing `ZipException: invalid LOC header` on lazy class loads
# (bitten live 2026-07-19). The gitignored copy is immutable until the next up.sh.
SNAPSHOT_DIR="${HERE}/.plugin-snapshot"
rm -rf "${SNAPSHOT_DIR}"
cp -r "${PLUGIN_DIR}" "${SNAPSHOT_DIR}"
log "Plugin snapshot: ${SNAPSHOT_DIR} (immune to concurrent gradle builds)"
export TRINO_DORIS_PLUGIN_DIR="${SNAPSHOT_DIR}"

# --- Up --------------------------------------------------------------------------
if ! docker network inspect trino-doris-dev_doris-net >/dev/null 2>&1; then
    err "network trino-doris-dev_doris-net not found — the local compose Doris cluster is not up."
    err "Either start it (../up.sh) for a zero-config first smoke, or edit docker-compose.yml"
    err "to drop the 'doris-net' attachment and use 'docker network connect' later."
    exit 1
fi

log "Starting Trino 484 with the plugin dir mounted read-only…"
# --force-recreate: compose does NOT recreate on unchanged config, and the plugin
# snapshot content changing is invisible to it — without this flag a --rebuild
# silently leaves the OLD plugin running in the live JVM (bitten 2026-07-19; the
# tell was an in-memory catalog surviving a supposed refresh).
docker compose -f "${COMPOSE}" up -d --force-recreate

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
