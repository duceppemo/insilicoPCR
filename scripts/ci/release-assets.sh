#!/usr/bin/env bash
# Fails when the release of a tag already has files: a run again (Re-run all jobs) rebuilt ZIPs that are not the
# same bytes (the jar and the Windows ZIP are dated by their build, the newest Java 25 update may differ), and
# replaced those Zenodo archived and the Bioconda recipe pins by checksum. No release of the tag yet (GitHub
# answers 404) passes; any other failure to ask (an outage, a rate limit) fails, rather than replace them.
#
# usage: scripts/ci/release-assets.sh <owner/repo> <tag>     (GH_TOKEN set, gh and jq on PATH)
set -euo pipefail

repo="${1:?usage: release-assets.sh <owner/repo> <tag>}"
tag="${2:?usage: release-assets.sh <owner/repo> <tag>}"

if release="$(gh api "repos/$repo/releases/tags/$tag" 2>/dev/null)"; then
  assets="$(jq '.assets | length' <<< "$release")"
  if (( assets > 0 )); then
    echo "::error::Release $tag already has $assets file(s): delete them to publish it again" >&2
    exit 1
  fi
  echo "Release $tag exists without files: they will be added"
elif [[ "$(jq -r '.status? // empty' <<< "$release" 2>/dev/null)" == "404" ]]; then
  echo "No release of $tag yet"
else
  echo "::error::Could not ask GitHub whether release $tag has files: ${release:-no answer}" >&2
  exit 1
fi
