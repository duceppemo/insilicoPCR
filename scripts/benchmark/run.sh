#!/usr/bin/env bash
# Runs the benchmark (docs/wiki/Benchmark.md) against this build, or against a release with --app.
#
#   scripts/benchmark/run.sh                                  the cases the tests run, against this build
#   scripts/benchmark/run.sh --tier standard --baseline benchmark/baselines/standard.tsv
#   scripts/benchmark/run.sh --tier standard --app ~/insilicoPCR-2.1.1/insilicoPCR-linux-x64
#   scripts/benchmark/run.sh --concordance samples.tsv --primers panel.fasta
#
# Needs a JDK of the project's Java version (JAVA_HOME or PATH), and Git LFS for the bundled tools.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
# Offline when the dependencies are already here (MAVEN_OFFLINE=1), as on a computer without a network.
MVN=(./mvnw -q)
if [[ "${MAVEN_OFFLINE:-}" == "1" ]]; then
  MVN+=(-o)
fi
"${MVN[@]}" test-compile dependency:build-classpath -Dmdep.outputFile=target/benchmark.classpath -Dmdep.includeScope=test
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$JAVA" -cp "target/classes:target/test-classes:$(cat target/benchmark.classpath)" \
  ca.canada.inspection.benchmark.Benchmark "$@"
