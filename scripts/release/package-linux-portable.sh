#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-0.6.0}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RELEASE_DIR="$ROOT/release"
STAGE_DIR="$ROOT/build/insilicoPCR-linux-x64"

cd "$ROOT"

./mvnw -B clean package -DskipTests

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR" "$RELEASE_DIR"

test -f "$ROOT/target/insilicoPCR.jar" || {
  echo "Missing JAR: $ROOT/target/insilicoPCR.jar"
  find "$ROOT/target" -maxdepth 2 -type f -print
  exit 1
}

cp "$ROOT/target/insilicoPCR.jar" "$STAGE_DIR/insilicoPCR.jar"
cp -r "$ROOT/target/lib" "$STAGE_DIR/lib"

cp -r "$ROOT/runtime/common" "$STAGE_DIR/runtime/" 2>/dev/null || true
cp -r "$ROOT/runtime/linux" "$STAGE_DIR/runtime/" 2>/dev/null || true
cp "$ROOT/README.md" "$STAGE_DIR/" 2>/dev/null || true
cp "$ROOT/CHANGELOG.md" "$STAGE_DIR/" 2>/dev/null || true
cp "$ROOT/LICENSE" "$STAGE_DIR/" 2>/dev/null || true
cp "$ROOT/LICENSE.txt" "$STAGE_DIR/" 2>/dev/null || true

cat > "$STAGE_DIR/run-insilicoPCR.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -x "$DIR/runtime/linux/jdk-26.0.1/bin/java" ]]; then
  JAVA="$DIR/runtime/linux/jdk-26.0.1/bin/java"
else
  echo "Could not find java binaries" >&2
  exit 1
fi

exec "$JAVA" -p "$DIR/lib" -m ca.canada.inspection.insilicopcr/ca.canada.inspection.dispatchpcr.Dispatcher "$@"
EOF

chmod +x "$STAGE_DIR/run-insilicoPCR.sh"

ARCHIVE="$RELEASE_DIR/insilicoPCR-${VERSION}-linux-x64.zip"
rm -f "$ARCHIVE" "$ARCHIVE.sha256"

cd "$ROOT/build"
zip -qr "$ARCHIVE" "$(basename "$STAGE_DIR")"

cd "$RELEASE_DIR"
sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256"

echo "Created:"
ls -lh "$ARCHIVE" "$ARCHIVE.sha256"
