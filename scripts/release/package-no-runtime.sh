#!/usr/bin/env bash
# The ZIP for package managers and containers (the Bioconda package is made from it): insilicoPCR and
# its libraries, without the Java runtime, BLAST+ and BBTools the portable ZIP bundles. Without a
# runtime folder, insilicoPCR runs the BLAST+ and BBTools found on PATH, in the Java runtime that runs it.
#
# Run after package-linux-portable.sh, which builds target/insilicoPCR.jar and target/lib.
#
# usage: scripts/release/package-no-runtime.sh [version]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="${1:-$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/pom.xml" | head -1)}"
RELEASE_DIR="$ROOT/release"
NAME="insilicoPCR-${VERSION}-linux-x64-no-runtime"
STAGE_DIR="$ROOT/build/$NAME"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -f "$ROOT/target/insilicoPCR.jar" ]] || fail "Missing target/insilicoPCR.jar: run package-linux-portable.sh first"
[[ -d "$ROOT/target/lib" ]] || fail "Missing target/lib: run package-linux-portable.sh first"

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR/third_party" "$RELEASE_DIR"

cp "$ROOT/target/insilicoPCR.jar" "$STAGE_DIR/"
cp -R "$ROOT/target/lib" "$STAGE_DIR/lib"
cp "$ROOT/README.md" "$ROOT/CHANGELOG.md" "$ROOT/LICENSE" "$ROOT/THIRD-PARTY-NOTICES.md" "$STAGE_DIR/"
# The libraries in lib/ are redistributed: their licenses go with them (Commons CLI has its own inside its jar).
cp -R "$ROOT/third_party/OpenJFX" "$STAGE_DIR/third_party/OpenJFX"

cat > "$STAGE_DIR/run-insilicoPCR.sh" <<'EOF'
#!/usr/bin/env bash
# insilicoPCR without a bundled runtime: it needs Java 25 or later (JAVA_HOME, or java on PATH), and
# runs the NCBI BLAST+ (blastn, makeblastdb) and BBTools (bbduk.sh) found on PATH.
set -euo pipefail
DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")" && pwd)"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="$(command -v java || true)"
fi
if [[ -z "$JAVA" ]]; then
  echo "insilicoPCR needs Java 25 or later: set JAVA_HOME, or put java on PATH." >&2
  exit 1
fi
FEATURE="$("$JAVA" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = //p')"
if [[ ! "$FEATURE" =~ ^[0-9]+$ || "$FEATURE" -lt 25 ]]; then
  echo "insilicoPCR needs Java 25 or later; $JAVA is Java ${FEATURE:-of an unknown version}." >&2
  exit 1
fi

# As in the portable ZIP: Java names files in the locale's encoding, so use UTF-8 when the locale does not.
if [[ "$(locale charmap 2>/dev/null)" != "UTF-8" ]] && locale -a 2>/dev/null | grep -qiE '^c\.utf-?8$'; then
  export LC_ALL=C.UTF-8
fi

exec "$JAVA" --enable-native-access=ca.canada.inspection.insilicopcr,javafx.graphics -p "$DIR/lib:$DIR/insilicoPCR.jar" -m ca.canada.inspection.insilicopcr/ca.canada.inspection.dispatchpcr.Dispatcher "$@"
EOF
chmod +x "$STAGE_DIR/run-insilicoPCR.sh"

{
  echo "insilicoPCR $VERSION, without a bundled runtime"
  echo "Needs: Java 25 or later, NCBI BLAST+ (blastn, makeblastdb) and BBTools (bbduk.sh) on PATH."
  echo "Built and tested with: Java 25, BLAST+ 2.17.0, BBTools 40.04."
  echo "JavaFX: $(ls "$STAGE_DIR/lib" | sed -n 's/^javafx-base-\(.*\)-linux.jar$/\1/p' | head -n 1) (Linux x64)"
} > "$STAGE_DIR/VERSIONS.txt"

ARCHIVE="$RELEASE_DIR/$NAME.zip"
rm -f "$ARCHIVE" "$ARCHIVE.sha256"

# Reproducible as the portable ZIP is: one entry order, each dated by the commit, in UTC.
export TZ=UTC
cd "$ROOT/build"
EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$ROOT" log -1 --format=%ct 2>/dev/null || date +%s)}"
find "$NAME" -exec touch -h -d "@$EPOCH" {} +
find "$NAME" | LC_ALL=C sort | zip -qX "$ARCHIVE" -@

cd "$RELEASE_DIR"
sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256"

echo "Created:"
ls -lh "$ARCHIVE" "$ARCHIVE.sha256"
