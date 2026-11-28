#!/usr/bin/env bash
# Fails when a test class that needs something of the computer was skipped, or did not run:
# they skip themselves (JUnit assumptions) when BLAST+ or BBTools is missing, when the example
# files are Git LFS pointers, without Chrome, or without a display. Nothing failed then, and a
# runner image without Chrome or xvfb, or an LFS quota, would have turned them off unseen.
#
# usage: scripts/ci/require-tests-ran.sh [--windows] [surefire reports folder]
#
# --windows: some tests skip themselves there by design (a stand-in program written as a shell
# script, a POSIX file permission), so a class passes when it ran at least one test unskipped:
# a missing BLAST+ or BBTools still skips all of EndToEndTest. The Chrome test is not required:
# it does not find the runner's Chrome.
set -euo pipefail

WINDOWS=false
if [[ "${1:-}" == "--windows" ]]; then
  WINDOWS=true
  shift
fi
REPORTS="${1:-target/surefire-reports}"
CLASSES=(
  ca.canada.inspection.pipeline.EndToEndTest         # BLAST+, BBTools, the example (LFS)
  ca.canada.inspection.gel.SyntheticGelImageTest     # a display
  ca.canada.inspection.insilicopcr.GelLoaderTest     # a display
  ca.canada.inspection.dispatchpcr.DispatcherTest    # BLAST+
  ca.canada.inspection.benchmark.BenchmarkTest       # BLAST+, BBTools
)
if ! $WINDOWS; then
  CLASSES+=(ca.canada.inspection.report.HtmlReportScriptTest) # Chrome
fi

failed=0
for class in "${CLASSES[@]}"; do
  xml="$REPORTS/TEST-$class.xml"
  if [[ ! -f "$xml" ]]; then
    echo "FAIL: $class did not run (no $xml)" >&2
    failed=1
    continue
  fi
  # The <testsuite> element's own counts.
  suite="$(grep -m1 -o '<testsuite [^>]*>' "$xml")"
  tests="$(sed -n 's/.* tests="\([0-9]*\)".*/\1/p' <<< "$suite")"
  skipped="$(sed -n 's/.* skipped="\([0-9]*\)".*/\1/p' <<< "$suite")"
  if [[ -z "$tests" || "$tests" -eq 0 ]] || { ! $WINDOWS && [[ "${skipped:-0}" -ne 0 ]]; } \
     || [[ "${skipped:-0}" -ge "$tests" ]]; then
    echo "FAIL: $class ran ${tests:-no} test(s), ${skipped:-?} skipped: what it needs is missing on this runner" >&2
    failed=1
  else
    echo "ok: $class, $tests test(s), ${skipped:-0} skipped"
  fi
done
exit "$failed"
