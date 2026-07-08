#!/usr/bin/env bash
set -euo pipefail

REQUIRE_BINARIES="${REQUIRE_BINARIES:-false}"

required_dirs=(
  "runtime/linux"
  "runtime/windows"
  "runtime/common"
)

if [[ "$REQUIRE_BINARIES" == "true" ]]; then
  required_dirs+=(
    "runtime/linux/blast/bin"
    "runtime/windows/blast/bin"
    "runtime/common/bbmap/bbtools.jar"
    "runtime/common/bbmap/bbduk.sh"
    "runtime/common/bbmap/bbmap.sh"
    "runtime/common/bbmap/tadpole.sh"
    "runtime/common/bbmap/calcmem.sh"
    "runtime/common/bbmap/javasetup.sh"
    "runtime/common/bbmap/config"
    "runtime/common/bbmap/resources"
  )
fi

for dir in "${required_dirs[@]}"; do
  if [[ ! -d "$dir" ]]; then
    echo "ERROR: Missing $dir"
    exit 1
  fi
done

echo "Runtime layout OK"
