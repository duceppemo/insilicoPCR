#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-0.6.0}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
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

  if [[ -f "$ROOT/runtime/common/bbmap/bbduk.sh" ]]; then
    require_executable "$ROOT/runtime/common/bbmap/bbduk.sh"
  elif [[ -f "$ROOT/runtime/common/bbmap/current/bbduk.sh" ]]; then
    require_executable "$ROOT/runtime/common/bbmap/current/bbduk.sh"
  else
    fail "Missing BBMap launcher: runtime/common/bbmap/bbduk.sh or runtime/common/bbmap/current/bbduk.sh"
  fi

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
cp -R "$JAVA_HOME" "$STAGE_DIR/runtime/linux/jdk"

mkdir -p "$STAGE_DIR/runtime/common" "$STAGE_DIR/runtime/linux"
cp -R "$ROOT/runtime/common/bbmap" "$STAGE_DIR/runtime/common/bbmap"
cp -R "$ROOT/runtime/linux/blast" "$STAGE_DIR/runtime/linux/blast"

cp "$ROOT/README.md" "$STAGE_DIR/" 2>/dev/null || true
cp "$ROOT/CHANGELOG.md" "$STAGE_DIR/" 2>/dev/null || true
cp "$ROOT/LICENSE" "$STAGE_DIR/" 2>/dev/null || true
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

exec "$JAVA" -p "$DIR/lib:$DIR/insilicoPCR.jar" -m ca.canada.inspection.insilicopcr/ca.canada.inspection.dispatchpcr.Dispatcher "$@"
EOF

chmod +x "$STAGE_DIR/run-insilicoPCR.sh"
find "$STAGE_DIR/runtime/linux/jdk/bin" -type f -exec chmod +x {} \; 2>/dev/null || true
find "$STAGE_DIR/runtime/common/bbmap" -name '*.sh' -exec chmod +x {} \; 2>/dev/null || true
find "$STAGE_DIR/runtime/linux/blast/bin" -type f -exec chmod +x {} \; 2>/dev/null || true

ARCHIVE="$RELEASE_DIR/insilicoPCR-${VERSION}-linux-x64.zip"
rm -f "$ARCHIVE" "$ARCHIVE.sha256"

cd "$ROOT/build"
zip -qr "$ARCHIVE" "$(basename "$STAGE_DIR")"

cd "$RELEASE_DIR"
sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256"

echo "Created:"
ls -lh "$ARCHIVE" "$ARCHIVE.sha256"
