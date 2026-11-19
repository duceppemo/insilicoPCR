#!/usr/bin/env bash
# Lists the programs the portable ZIPs bundle that have a newer version out: Java 25 (Temurin, the
# newest update of the LTS line), NCBI BLAST+ and BBTools (the newest on Bioconda, which follows
# their releases). One line per program, nothing when the release is up to date.
#
#   scripts/ci/newer-versions.sh <VERSIONS.txt of a release>
#
# The monthly CI run gives it the VERSIONS.txt of the latest release (scripts/ci/remote-zip-entry.py)
# and opens an issue from its lines. Needs curl, python3 and conda (CONDA_EXE, or conda on PATH).
set -euo pipefail

if [[ $# -ne 1 || ! -f "$1" ]]; then
  echo "Usage: $0 <VERSIONS.txt of a release>" >&2
  exit 2
fi
versions="$1"
conda="${CONDA_EXE:-$(command -v conda || true)}"
[[ -n "$conda" ]] || { echo "conda not found: set CONDA_EXE or put conda on PATH" >&2; exit 2; }

# The version a line of VERSIONS.txt gives, e.g. "BLAST+: blastn: 2.17.0+" -> 2.17.0.
bundled() {
  local value
  value="$(sed -n "s/^$1: //p" "$versions" | grep -oE "$2" | head -n 1 || true)"
  [[ -n "$value" ]] || { echo "$versions: no $1 version" >&2; exit 1; }
  printf '%s\n' "$value"
}
java_bundled="$(bundled Java 'Temurin-[0-9][^ )]*')"
java_bundled="${java_bundled#Temurin-}"
blast_bundled="$(bundled 'BLAST+' '[0-9]+(\.[0-9]+)+')"
bbtools_bundled="$(bundled BBTools '[0-9]+(\.[0-9]+)+')"

# The newest Temurin 25 for Linux x64, the build CI bundles (setup-java with check-latest).
java_newest="$(curl -fsSL --retry 3 \
  'https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse' |
  python3 -I -c 'import json, sys; print(json.load(sys.stdin)[0]["release_name"].removeprefix("jdk-"))')"

# The newest version of a Bioconda package.
newest_on_bioconda() {
  "$conda" search --override-channels -c conda-forge -c bioconda --json "$1" |
    python3 -I -c 'import json, sys; print("\n".join({p["version"] for p in json.load(sys.stdin)[sys.argv[1]]}))' "$1" |
    sort -V | tail -n 1
}
blast_newest="$(newest_on_bioconda blast)"
bbtools_newest="$(newest_on_bioconda bbmap)"

report() {
  [[ -n "$3" && "$2" != "$3" ]] || return 0
  # Newer, not merely different: the newest sorts last.
  [[ "$(printf '%s\n%s\n' "$2" "$3" | sort -V | tail -n 1)" == "$3" ]] || return 0
  echo "$1 $3 (the release bundles $2)"
}
report Java "$java_bundled" "$java_newest"
report BLAST+ "$blast_bundled" "$blast_newest"
report BBTools "$bbtools_bundled" "$bbtools_newest"
