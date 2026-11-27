#!/usr/bin/env bash
# The version of a tool a portable ZIP bundles, from its VERSIONS.txt, written as the ZIP is built:
# usage: scripts/ci/bundled-version.sh <VERSIONS.txt> 'BLAST+'|BBTools
set -euo pipefail

versions="${1:?usage: bundled-version.sh <VERSIONS.txt> 'BLAST+'|BBTools}"
tool="${2:?usage: bundled-version.sh <VERSIONS.txt> 'BLAST+'|BBTools}"
value="$(sed -n "s/^$tool: //p" "$versions" | grep -oE '[0-9]+(\.[0-9]+)+' | head -n 1 || true)"
[[ -n "$value" ]] || { echo "$versions: no $tool version" >&2; exit 1; }
printf '%s\n' "$value"
