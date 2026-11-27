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
    "runtime/common/bbmap/networks/bbmerge.bbnet.gz"
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

if [[ "$REQUIRE_BINARIES" == "true" ]]; then
  # A checkout without Git LFS (or over its quota) holds 130-byte pointer files where the tools are: the
  # layout looked complete, and the tests needing the tools skipped, or the smoke test failed minutes later.
  pointers=()
  while IFS= read -r -d '' file; do
    if head -c 40 "$file" 2>/dev/null | grep -qa '^version https://git-lfs\.github\.com/spec/'; then
      pointers+=("$file")
    fi
  done < <(find runtime -type f -size -1024c -print0)
  if (( ${#pointers[@]} > 0 )); then
    echo "ERROR: Git LFS pointers, not the files (check out with LFS): ${pointers[*]:0:5}"
    exit 1
  fi
fi

echo "Runtime layout OK"
