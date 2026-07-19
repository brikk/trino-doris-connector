#!/usr/bin/env bash
# Tear down the OPTIONAL trino-doris multi-FE failover overlay (frees memory).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${HERE}/up.sh" --down
