#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-0.0.0-ci}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RELEASE_DIR="$ROOT/release"
STAGE_DIR="$ROOT/target/insilicoPCR-linux-x64"

rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR" "$RELEASE_DIR"

cd "$ROOT"

./mvnw -B clean package -DskipTests

cp target/insilicoPCR.jar "$STAGE_DIR/"
cp -r runtime "$STAGE_DIR/" 2>/dev/null || true
cp README.md "$STAGE_DIR/" 2>/dev/null || true
cp CHANGELOG.md "$STAGE_DIR/" 2>/dev/null || true
cp LICENSE "$STAGE_DIR/" 2>/dev/null || true
cp LICENSE.txt "$STAGE_DIR/" 2>/dev/null || true

cat > "$STAGE_DIR/run-insilicoPCR.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -x "$DIR/runtime/linux/jdk/bin/java" ]]; then
  JAVA="$DIR/runtime/linux/jdk/bin/java"
elif [[ -x "$DIR/runtime/linux/jre/bin/java" ]]; then
  JAVA="$DIR/runtime/linux/jre/bin/java"
else
  JAVA="java"
fi

exec "$JAVA" -jar "$DIR/insilicoPCR.jar" "$@"
EOF

chmod +x "$STAGE_DIR/run-insilicoPCR.sh"

ARCHIVE="$RELEASE_DIR/insilicoPCR-${VERSION}-linux-x64.zip"
rm -f "$ARCHIVE" "$ARCHIVE.sha256"

cd "$STAGE_DIR/.."
zip -qr "$ARCHIVE" "$(basename "$STAGE_DIR")"

cd "$RELEASE_DIR"
sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256"

echo "Created:"
ls -lh "$ARCHIVE" "$ARCHIVE.sha256"
