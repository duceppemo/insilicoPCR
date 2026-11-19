#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# The version the ZIP is named for: given (CI gives the tag's), or the project's own.
VERSION="${1:-$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/pom.xml" | head -1)}"
RELEASE_DIR="$ROOT/release"
STAGE_DIR="$ROOT/build/insilicoPCR-linux-x64"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

require_file() {
  local path="$1"
  [[ -f "$path" ]] || fail "Missing required file: $path"
}

require_dir() {
  local path="$1"
  [[ -d "$path" ]] || fail "Missing required directory: $path"
}

require_executable() {
  local path="$1"
  require_file "$path"
  chmod +x "$path" 2>/dev/null || true
}

validate_bundled_tools() {
  require_dir "$ROOT/runtime/common/bbmap"
  require_executable "$ROOT/runtime/common/bbmap/bbduk.sh"
  require_file "$ROOT/runtime/common/bbmap/bbtools.jar"
  require_file "$ROOT/runtime/common/bbmap/bbmap.sh"
  require_file "$ROOT/runtime/common/bbmap/tadpole.sh"
  require_file "$ROOT/runtime/common/bbmap/calcmem.sh"
  require_file "$ROOT/runtime/common/bbmap/javasetup.sh"
  require_file "$ROOT/runtime/common/bbmap/networks/bbmerge.bbnet.gz"
  require_dir  "$ROOT/runtime/common/bbmap/config"
  require_dir  "$ROOT/runtime/common/bbmap/resources"

  require_dir "$ROOT/runtime/linux/blast/bin"
  require_executable "$ROOT/runtime/linux/blast/bin/blastn"
  require_executable "$ROOT/runtime/linux/blast/bin/makeblastdb"
}

cd "$ROOT"

validate_bundled_tools

./mvnw -B clean package -DskipTests

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR" "$RELEASE_DIR" "$STAGE_DIR/runtime/linux"

require_file "$ROOT/target/insilicoPCR.jar"
require_dir "$ROOT/target/lib"

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  fail "JAVA_HOME must point to the JDK that should be bundled in the portable release."
fi

cp "$ROOT/target/insilicoPCR.jar" "$STAGE_DIR/insilicoPCR.jar"
cp -R "$ROOT/target/lib" "$STAGE_DIR/lib"
# Its files, links followed: JAVA_HOME may be a link (setup-java links a JDK the runner image has), and
# cp -R would copy the link, not the JDK.
mkdir -p "$STAGE_DIR/runtime/linux/jdk"
cp -RL "$JAVA_HOME/." "$STAGE_DIR/runtime/linux/jdk/"

mkdir -p "$STAGE_DIR/runtime/common" "$STAGE_DIR/runtime/linux"
cp -R "$ROOT/runtime/common/bbmap" "$STAGE_DIR/runtime/common/bbmap"
cp -R "$ROOT/runtime/linux/blast" "$STAGE_DIR/runtime/linux/blast"

cp "$ROOT/README.md" "$STAGE_DIR/" 2>/dev/null || true
cp "$ROOT/CHANGELOG.md" "$STAGE_DIR/" 2>/dev/null || true
cp "$ROOT/LICENSE" "$STAGE_DIR/" 2>/dev/null || true
# The bundled programs' and libraries' licenses: their redistribution requires them.
cp "$ROOT/THIRD-PARTY-NOTICES.md" "$STAGE_DIR/"
cp -R "$ROOT/third_party" "$STAGE_DIR/third_party"
rm -rf "$STAGE_DIR/third_party/nghttp2" # a library of the Windows BLAST+ only
cp "$ROOT/LICENSE.txt" "$STAGE_DIR/" 2>/dev/null || true
cp "$ROOT/docs/runtime-layout.md" "$STAGE_DIR/" 2>/dev/null || true

cat > "$STAGE_DIR/run-insilicoPCR.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA="$DIR/runtime/linux/jdk/bin/java"

if [[ ! -x "$JAVA" ]]; then
  echo "Could not find bundled Java runtime at: $JAVA" >&2
  exit 1
fi

# Java names files in the locale's encoding: under LANG=C (cron, batch schedulers, containers),
# a file or folder name with an accented letter cannot be opened. Use UTF-8 when the locale does not.
if [[ "$(locale charmap 2>/dev/null)" != "UTF-8" ]] && locale -a 2>/dev/null | grep -qiE '^c\.utf-?8$'; then
  export LC_ALL=C.UTF-8
fi

exec "$JAVA" --enable-native-access=ca.canada.inspection.insilicopcr,javafx.graphics -p "$DIR/lib:$DIR/insilicoPCR.jar" -m ca.canada.inspection.insilicopcr/ca.canada.inspection.dispatchpcr.Dispatcher "$@"
EOF

chmod +x "$STAGE_DIR/run-insilicoPCR.sh"
find "$STAGE_DIR/runtime/linux/jdk/bin" -type f -exec chmod +x {} \; 2>/dev/null || true
find "$STAGE_DIR/runtime/common/bbmap" -name '*.sh' -exec chmod +x {} \; 2>/dev/null || true
find "$STAGE_DIR/runtime/linux/blast/bin" -type f -exec chmod +x {} \; 2>/dev/null || true

ARCHIVE="$RELEASE_DIR/insilicoPCR-${VERSION}-linux-x64.zip"
rm -f "$ARCHIVE" "$ARCHIVE.sha256"

# The versions bundled, for whoever audits a result.
{
  echo "insilicoPCR $VERSION"
  echo "Java: $("$STAGE_DIR/runtime/linux/jdk/bin/java" -version 2>&1 | head -n 2 | tail -n 1)"
  echo "BLAST+: $("$STAGE_DIR/runtime/linux/blast/bin/blastn" -version 2>&1 | head -n 1)"
  echo "BBTools: $("$STAGE_DIR/runtime/linux/jdk/bin/java" -cp "$STAGE_DIR/runtime/common/bbmap/bbtools.jar" jgi.BBDuk --version 2>&1 | grep -o 'BBTools version [0-9.]*' | head -n 1 || true)"
  echo "JavaFX: $(ls "$STAGE_DIR/lib" | sed -n 's/^javafx-base-\(.*\)-linux.jar$/\1/p' | head -n 1)"
} > "$STAGE_DIR/VERSIONS.txt"

# The same commit gives the same archive: entries in one order, each dated by the commit (not by the build).
# In UTC: zip -X keeps the DOS dates only, which are local time (two time zones gave two archives).
export TZ=UTC
cd "$ROOT/build"
EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$ROOT" log -1 --format=%ct 2>/dev/null || date +%s)}"
find "$(basename "$STAGE_DIR")" -exec touch -h -d "@$EPOCH" {} +
find "$(basename "$STAGE_DIR")" | LC_ALL=C sort | zip -qX "$ARCHIVE" -@

cd "$RELEASE_DIR"
sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256"

echo "Created:"
ls -lh "$ARCHIVE" "$ARCHIVE.sha256"
