#!/usr/bin/env bash
set -euo pipefail

APP_NAME="insilicoPCR"
APP_MODULE="ca.canada.inspection.insilicopcr"
MAIN_CLASS="ca.canada.inspection.dispatchpcr.Dispatcher"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-$(grep -m1 '<version>' "$ROOT/pom.xml" | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/')}"
DO_INSTALLER="${DO_INSTALLER:-false}"

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/jlink" || ! -x "$JAVA_HOME/bin/jpackage" ]]; then
  echo "ERROR: JAVA_HOME must point to a JDK 26+ that contains jlink and jpackage." >&2
  echo "Example: export JAVA_HOME=/home/marco/.jdks/openjdk-26.0.1" >&2
  exit 1
fi

RUNTIME_COMMON="$ROOT/runtime/common"
RUNTIME_PLATFORM="$ROOT/runtime/linux"
BBMAP_SRC="$RUNTIME_COMMON/bbmap"
BLAST_SRC="$RUNTIME_PLATFORM/blast"

[[ -d "$BBMAP_SRC" ]] || { echo "ERROR: BBMap not found: $BBMAP_SRC" >&2; exit 1; }
[[ -d "$BLAST_SRC/bin" ]] || { echo "ERROR: BLAST bin not found: $BLAST_SRC/bin" >&2; exit 1; }

cd "$ROOT"
./mvnw -Prelease-linux -DskipTests clean package

APP_JAR="$ROOT/target/${APP_NAME}.jar"
LIB_DIR="$ROOT/target/lib"
IMAGE_RUNTIME="$ROOT/target/jlink-runtime-linux-x64"
JPACKAGE_DIR="$ROOT/target/jpackage-linux"
APP_CONTENT="$ROOT/target/release-app-content"
RELEASE_DIR="$ROOT/release"
APP_IMAGE_NAME="${APP_NAME}-${VERSION}-linux-x64"
APP_IMAGE="$JPACKAGE_DIR/$APP_NAME"
FINAL_IMAGE="$RELEASE_DIR/$APP_IMAGE_NAME"

rm -rf "$IMAGE_RUNTIME" "$JPACKAGE_DIR" "$APP_CONTENT" "$FINAL_IMAGE"
mkdir -p "$APP_CONTENT/dependencies" "$RELEASE_DIR"
cp -R "$BBMAP_SRC" "$APP_CONTENT/dependencies/bbmap"
cp -R "$BLAST_SRC" "$APP_CONTENT/dependencies/blast"
[[ -f README.md ]] && cp README.md "$APP_CONTENT/"
[[ -f LICENSE ]] && cp LICENSE "$APP_CONTENT/"
[[ -f CHANGELOG.md ]] && cp CHANGELOG.md "$APP_CONTENT/"

MODULE_PATH="$JAVA_HOME/jmods:$APP_JAR:$LIB_DIR"
MODULES="$($JAVA_HOME/bin/jdeps \
  --ignore-missing-deps \
  --multi-release 26 \
  --module-path "$LIB_DIR" \
  --print-module-deps "$APP_JAR" 2>/dev/null || true)"

if [[ -z "$MODULES" ]]; then
  MODULES="java.base,java.desktop,java.logging,java.xml,java.naming,jdk.crypto.ec,jdk.localedata,javafx.controls,javafx.fxml"
else
  MODULES="$MODULES,jdk.crypto.ec,jdk.localedata"
fi

"$JAVA_HOME/bin/jlink" \
  --module-path "$MODULE_PATH" \
  --add-modules "$MODULES" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=zip-6 \
  --output "$IMAGE_RUNTIME"

"$JAVA_HOME/bin/jpackage" \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "$VERSION" \
  --vendor "Canadian Food Inspection Agency" \
  --description "Portable JavaFX application for in silico PCR analysis." \
  --dest "$JPACKAGE_DIR" \
  --runtime-image "$IMAGE_RUNTIME" \
  --module-path "$APP_JAR:$LIB_DIR" \
  --module "$APP_MODULE/$MAIN_CLASS" \
  --java-options "--enable-native-access=javafx.graphics" \
  --app-content "$APP_CONTENT"

mv "$APP_IMAGE" "$FINAL_IMAGE"

# Ensure external bioinformatics tools are executable in the portable image.
find "$FINAL_IMAGE/dependencies/bbmap" -type f -name '*.sh' -exec chmod +x {} + || true
find "$FINAL_IMAGE/dependencies/blast/bin" -type f -exec chmod +x {} + || true

(
  cd "$RELEASE_DIR"
  rm -f "$APP_IMAGE_NAME.zip" "$APP_IMAGE_NAME.tar.gz" "$APP_IMAGE_NAME.sha256"
  if command -v zip >/dev/null 2>&1; then
    zip -qr "$APP_IMAGE_NAME.zip" "$APP_IMAGE_NAME"
    sha256sum "$APP_IMAGE_NAME.zip" > "$APP_IMAGE_NAME.sha256"
  else
    tar -czf "$APP_IMAGE_NAME.tar.gz" "$APP_IMAGE_NAME"
    sha256sum "$APP_IMAGE_NAME.tar.gz" > "$APP_IMAGE_NAME.sha256"
  fi
)

if [[ "$DO_INSTALLER" == "true" ]]; then
  "$JAVA_HOME/bin/jpackage" \
    --type deb \
    --name "$APP_NAME" \
    --app-version "$VERSION" \
    --vendor "Canadian Food Inspection Agency" \
    --description "Portable JavaFX application for in silico PCR analysis." \
    --dest "$RELEASE_DIR" \
    --app-image "$FINAL_IMAGE" \
    --linux-shortcut || echo "WARNING: deb creation failed; portable app-image zip was still created."
fi

echo "Release artifacts written to: $RELEASE_DIR"
ls -lh "$RELEASE_DIR" | sed 's/^/  /'
