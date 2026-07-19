#!/usr/bin/env bash
# Tear down the isolated stock Doris 4.1.3 trino-doris probe cluster.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${HERE}/up.sh" --down
