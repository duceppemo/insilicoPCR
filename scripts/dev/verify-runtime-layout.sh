#!/usr/bin/env bash
set -euo pipefail

REQUIRE_BINARIES="${REQUIRE_BINARIES:-false}"

required_dirs=(
  "runtime/linux"
  "runtime/windows"
  "runtime/common"
)

required_files=()

if [[ "$REQUIRE_BINARIES" == "true" ]]; then
  required_dirs+=(
    "runtime/linux/blast/bin"
    "runtime/windows/blast/bin"
    "runtime/common/bbmap/config"
    "runtime/common/bbmap/resources"
  )

  required_files+=(
    "runtime/common/bbmap/bbtools.jar"
    "runtime/common/bbmap/bbduk.sh"
    "runtime/common/bbmap/bbmap.sh"
    "runtime/common/bbmap/tadpole.sh"
    "runtime/common/bbmap/calcmem.sh"
    "runtime/common/bbmap/javasetup.sh"
  )
fi

for dir in "${required_dirs[@]}"; do
  if [[ ! -d "$dir" ]]; then
    echo "ERROR: Missing directory: $dir"
    exit 1
  fi
done

for file in "${required_files[@]}"; do
  if [[ ! -f "$file" ]]; then
    echo "ERROR: Missing file: $file"
    exit 1
  fi
done

echo "Runtime layout OK"
