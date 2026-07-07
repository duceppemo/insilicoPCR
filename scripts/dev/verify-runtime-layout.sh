#!/usr/bin/env bash
set -euo pipefail

REQUIRE_BINARIES="${REQUIRE_BINARIES:-false}"

required_dirs=(
  "runtime"
  "runtime/linux"
  "runtime/windows"
  "runtime/common"
)

if [[ "$REQUIRE_BINARIES" == "true" ]]; then
  required_dirs+=(
    "runtime/linux/blast/bin"
    "runtime/windows/blast/bin"
    "runtime/common/bbmap"
  )
fi

for dir in "${required_dirs[@]}"; do
  if [[ ! -d "$dir" ]]; then
    echo "ERROR: Missing $dir"
    exit 1
  fi
done

echo "Runtime layout OK"
