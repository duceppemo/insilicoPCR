#!/usr/bin/env bash
# Checks a release tag before anything is built for it, so that a mistake fails in seconds rather than
# after the builds (the version was compared only by the smoke test, minutes in):
#  - the tag is vX.Y.Z (or vX.Y.Z-suffix);
#  - pom.xml's version is the tag's, which the program reports;
#  - CHANGELOG.md has the version's heading (## X.Y.Z (date));
#  - the tagged commit is on master (pushed before the tag, as the release procedure does);
#  - docs/release-notes/X.Y.Z.md exists, or a warning: the release then has only GitHub's list of pull requests.
#
# usage: scripts/ci/release-preflight.sh <tag> [commit] [branch]
set -euo pipefail

tag="${1:?usage: release-preflight.sh <tag> [commit] [branch]}"
commit="${2:-HEAD}"
branch="${3:-origin/master}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

failed=0
fail() {
  echo "FAIL: $*" >&2
  failed=1
}

if [[ ! "$tag" =~ ^v([0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?)$ ]]; then
  echo "FAIL: tag $tag is not vX.Y.Z or vX.Y.Z-suffix" >&2
  exit 1
fi
version="${BASH_REMATCH[1]}"

pom="$(python3 -I "$ROOT/scripts/ci/pom-version.py" "$ROOT/pom.xml")"
[[ "$pom" == "$version" ]] || fail "pom.xml is version $pom, the tag $tag"

grep -qE "^## ${version//./\\.} \(" "$ROOT/CHANGELOG.md" \
  || fail "CHANGELOG.md has no heading '## $version (date)'"

if git -C "$ROOT" rev-parse --verify --quiet "$branch" > /dev/null; then
  git -C "$ROOT" merge-base --is-ancestor "$commit" "$branch" \
    || fail "the tagged commit is not on $branch: push master before the tag"
else
  fail "no $branch to check the tagged commit against"
fi

if [[ ! -f "$ROOT/docs/release-notes/$version.md" ]]; then
  echo "::warning::docs/release-notes/$version.md is missing: the release has only the list of pull requests"
fi

if (( failed )); then
  exit 1
fi
echo "Release $tag: pom.xml, CHANGELOG.md and $branch agree"
