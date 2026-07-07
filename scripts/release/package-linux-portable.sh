#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-0.6.0}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RELEASE_DIR="$ROOT/release"
STAGE_DIR="$ROOT/build/insilicoPCR-linux-x64"

cd "$ROOT"

./mvnw -B clean package -DskipTests

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR" "$RELEASE_DIR" "$STAGE_DIR/runtime/linux"

test -f "$ROOT/target/insilicoPCR.jar" || {
  echo "Missing JAR: $ROOT/target/insilicoPCR.jar"
  find "$ROOT/target" -maxdepth 2 -type f -print
  exit 1
}

test -d "$ROOT/target/lib" || {
  echo "Missing runtime dependencies: $ROOT/target/lib"
  exit 1
}

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JAVA_HOME must point to the JDK that should be bundled in the portable release." >&2
  exit 1
fi

cp "$ROOT/target/insilicoPCR.jar" "$STAGE_DIR/insilicoPCR.jar"
cp -r "$ROOT/target/lib" "$STAGE_DIR/lib"
cp -R "$JAVA_HOME" "$STAGE_DIR/runtime/linux/jdk"

cp -r "$ROOT/runtime/common" "$STAGE_DIR/runtime/" 2>/dev/null || true
cp -r "$ROOT/runtime/linux/blast" "$STAGE_DIR/runtime/linux/" 2>/dev/null || true
cp "$ROOT/README.md" "$STAGE_DIR/" 2>/dev/null || true
cp "$ROOT/CHANGELOG.md" "$STAGE_DIR/" 2>/dev/null || true
cp "$ROOT/LICENSE" "$STAGE_DIR/" 2>/dev/null || true
cp "$ROOT/LICENSE.txt" "$STAGE_DIR/" 2>/dev/null || true

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

ARCHIVE="$RELEASE_DIR/insilicoPCR-${VERSION}-linux-x64.zip"
rm -f "$ARCHIVE" "$ARCHIVE.sha256"

cd "$ROOT/build"
zip -qr "$ARCHIVE" "$(basename "$STAGE_DIR")"

cd "$RELEASE_DIR"
sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256"

echo "Created:"
ls -lh "$ARCHIVE" "$ARCHIVE.sha256"
