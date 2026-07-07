#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -d "$ROOT/runtime/common/bbmap" ]] || fail "Missing runtime/common/bbmap"
[[ -f "$ROOT/runtime/common/bbmap/bbduk.sh" || -f "$ROOT/runtime/common/bbmap/current/bbduk.sh" ]] || fail "Missing BBMap bbduk.sh"
[[ -d "$ROOT/runtime/linux/blast/bin" ]] || fail "Missing runtime/linux/blast/bin"
[[ -f "$ROOT/runtime/linux/blast/bin/blastn" ]] || fail "Missing Linux blastn"
[[ -f "$ROOT/runtime/linux/blast/bin/makeblastdb" ]] || fail "Missing Linux makeblastdb"
[[ -d "$ROOT/runtime/windows/blast/bin" ]] || fail "Missing runtime/windows/blast/bin"
[[ -f "$ROOT/runtime/windows/blast/bin/blastn.exe" ]] || fail "Missing Windows blastn.exe"
[[ -f "$ROOT/runtime/windows/blast/bin/makeblastdb.exe" ]] || fail "Missing Windows makeblastdb.exe"

echo "Runtime layout is valid."
