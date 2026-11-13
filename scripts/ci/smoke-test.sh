#!/usr/bin/env bash
# Runs a portable release ZIP on the five-isolate example and compares the report with
# the expected one. Works on Linux and, under Git Bash, on Windows.
#
# usage: scripts/ci/smoke-test.sh <portable ZIP> [work directory]
set -euo pipefail

ZIP="${1:?usage: smoke-test.sh <portable ZIP> [work directory]}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EXAMPLE="$ROOT/docs/examples/five-isolates"
WORK="${2:-$(mktemp -d)}"

mkdir -p "$WORK"
rm -rf "$WORK/app" "$WORK/genomes" "$WORK/results" "$WORK/broken" "$WORK/broken-results"
unzip -q "$ZIP" -d "$WORK/app"

JAR="$(find "$WORK/app" -maxdepth 2 -name insilicoPCR.jar | head -n 1)"
[[ -n "$JAR" ]] || { echo "FAIL: no insilicoPCR.jar in $ZIP" >&2; exit 1; }
APP="$(dirname "$JAR")"
JAVA="$(find "$APP/runtime" -maxdepth 4 \( -name java -o -name java.exe \) -path '*jdk/bin/*' | head -n 1)"
[[ -n "$JAVA" ]] || { echo "FAIL: no bundled Java under $APP/runtime" >&2; exit 1; }

echo "== version"
"$JAVA" -jar "$JAR" --version

# Two of the five genomes are given gzipped, to cover both ways an assembly is read.
mkdir -p "$WORK/genomes"
cp "$EXAMPLE"/genomes/*.fasta "$WORK/genomes/"
gzip "$WORK/genomes/isolate_02.fasta" "$WORK/genomes/isolate_04.fasta"

echo "== run on the five-isolate example"
"$JAVA" -jar "$JAR" -i "$WORK/genomes" -p "$EXAMPLE/primers.fasta" -o "$WORK/results" -t 2

REPORT="$WORK/results/consolidated_report/report.tsv"
[[ -s "$REPORT" ]] || { echo "FAIL: no report at $REPORT" >&2; exit 1; }
[[ -s "$WORK/results/consolidated_report/report.html" ]] || { echo "FAIL: no HTML report" >&2; exit 1; }

# Line endings differ between platforms (and with the checkout settings); the rows must not.
if ! diff <(tr -d '\r' < "$EXAMPLE/report.tsv") <(tr -d '\r' < "$REPORT"); then
  echo "FAIL: report.tsv differs from docs/examples/five-isolates/report.tsv" >&2
  exit 1
fi

LEFT="$(find "$WORK/results" -name 'blast_query_*' | wc -l)"
[[ "$LEFT" -eq 0 ]] || { echo "FAIL: $LEFT decompressed BLAST queries left in the results" >&2; exit 1; }

echo "== a search that fails ends the run with a message"
mkdir -p "$WORK/broken"
printf '>only_a_header\n' > "$WORK/broken/broken.fasta"
set +e
"$JAVA" -jar "$JAR" -i "$WORK/broken" -p "$EXAMPLE/primers.fasta" -o "$WORK/broken-results" -t 1 \
  > "$WORK/broken.log" 2>&1
CODE=$?
set -e
if [[ "$CODE" -ne 1 ]] || ! grep -q '^Error: BLAST failed' "$WORK/broken.log"; then
  cat "$WORK/broken.log" >&2
  echo "FAIL: expected exit code 1 and an 'Error: BLAST failed' line, got exit code $CODE" >&2
  exit 1
fi

echo "Smoke test passed: $(basename "$ZIP")"
