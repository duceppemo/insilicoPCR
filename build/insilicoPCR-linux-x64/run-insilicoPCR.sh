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

exec "$JAVA" -p "$DIR/lib" -m ca.canada.inspection.insilicopcr/ca.canada.inspection.dispatchpcr.Dispatcher "$@"
