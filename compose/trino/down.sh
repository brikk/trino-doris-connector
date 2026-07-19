#!/usr/bin/env bash
# Tear down the manual trino-doris smoke sandbox (containers + volumes).
# Alias for `./up.sh --down`.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${HERE}/up.sh" --down
