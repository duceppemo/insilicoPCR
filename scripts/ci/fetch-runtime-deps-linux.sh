#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$ROOT/target/ci-runtime-downloads/linux"
BBMAP_URL="${BBMAP_URL:-https://sourceforge.net/projects/bbmap/files/BBMap_39.94.tar.gz/download}"
BLAST_BASE_URL="${BLAST_BASE_URL:-https://ftp.ncbi.nlm.nih.gov/blast/executables/blast+/LATEST/}"
BLAST_URL="${BLAST_LINUX_URL:-}"

rm -rf "$WORK" "$ROOT/runtime/common/bbmap" "$ROOT/runtime/linux/blast"
mkdir -p "$WORK" "$ROOT/runtime/common" "$ROOT/runtime/linux"

cd "$WORK"

echo "Downloading BBMap from: $BBMAP_URL"
curl -fL --retry 3 -o bbmap.tar.gz "$BBMAP_URL"
tar -xzf bbmap.tar.gz
BBMAP_DIR="$(find . -maxdepth 2 -type d \( -iname 'bbmap' -o -iname 'bbmap_*' \) | head -n 1)"
if [[ -z "$BBMAP_DIR" ]]; then
  echo "ERROR: Unable to locate extracted BBMap directory." >&2
  exit 1
fi
mv "$BBMAP_DIR" "$ROOT/runtime/common/bbmap"

if [[ -z "$BLAST_URL" ]]; then
  echo "Resolving latest Linux BLAST+ package from: $BLAST_BASE_URL"
  BLAST_FILE="$(curl -fsSL "$BLAST_BASE_URL" | grep -Eo 'ncbi-blast-[^"<> ]+-x64-linux\.tar\.gz' | sort -V | tail -n 1)"
  if [[ -z "$BLAST_FILE" ]]; then
    echo "ERROR: Could not resolve latest Linux BLAST+ package. Set BLAST_LINUX_URL explicitly." >&2
    exit 1
  fi
  BLAST_URL="$BLAST_BASE_URL$BLAST_FILE"
fi

echo "Downloading BLAST+ from: $BLAST_URL"
curl -fL --retry 3 -o blast-linux.tar.gz "$BLAST_URL"
tar -xzf blast-linux.tar.gz
BLAST_DIR="$(find . -maxdepth 1 -type d -name 'ncbi-blast-*' | head -n 1)"
if [[ -z "$BLAST_DIR" ]]; then
  echo "ERROR: Unable to locate extracted BLAST+ directory." >&2
  exit 1
fi
mv "$BLAST_DIR" "$ROOT/runtime/linux/blast"
chmod +x "$ROOT/runtime/linux/blast/bin/"* || true
chmod +x "$ROOT/runtime/common/bbmap/"*.sh || true

echo "Runtime dependencies prepared:"
find "$ROOT/runtime" -maxdepth 3 -type d -print
