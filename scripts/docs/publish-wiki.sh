#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WIKI_REMOTE="${WIKI_REMOTE:-https://github.com/duceppemo/insilicoPCR.wiki.git}"
WORKDIR="${WIKI_WORKDIR:-/tmp/insilicoPCR.wiki}"

if [[ ! -d "$ROOT/docs/wiki" ]]; then
  echo "ERROR: docs/wiki directory not found under $ROOT" >&2
  exit 1
fi

if [[ ! -d "$WORKDIR/.git" ]]; then
  # Only an empty or missing folder is cloned into: a mistyped WIKI_WORKDIR was deleted, whatever it held.
  if [[ -e "$WORKDIR" ]] && [[ -n "$(ls -A "$WORKDIR" 2>/dev/null)" ]]; then
    echo "ERROR: $WORKDIR is not a clone of the wiki and is not empty: give an empty or new folder" >&2
    exit 1
  fi
  rm -rf "$WORKDIR"
  git clone "$WIKI_REMOTE" "$WORKDIR"
else
  # A clone of the wiki only: any other clone given by mistake (the project's own) was reset, overwritten,
  # committed and pushed to its own remote.
  if [[ "$(git -C "$WORKDIR" remote get-url origin 2>/dev/null)" != "$WIKI_REMOTE" ]]; then
    echo "ERROR: $WORKDIR is a git clone of $(git -C "$WORKDIR" remote get-url origin 2>/dev/null || echo 'no origin'),\
 not of $WIKI_REMOTE: give the wiki's clone, or an empty or new folder" >&2
    exit 1
  fi
  # A clone kept from an earlier publish: brought up to date, or a page changed on the wiki since
  # would make the push fail (or the copy below drop that change from the clone).
  git -C "$WORKDIR" fetch --quiet origin
  git -C "$WORKDIR" reset --quiet --hard "origin/$(git -C "$WORKDIR" rev-parse --abbrev-ref HEAD)"
fi

rsync -av --delete \
  --exclude='.git/' \
  "$ROOT/docs/wiki/" \
  "$WORKDIR/"

cd "$WORKDIR"

git add .

if git diff --cached --quiet; then
  echo "Wiki is already up to date."
  exit 0
fi

git commit -m "Update Wiki documentation"
git push

echo "Published Wiki documentation to $WIKI_REMOTE"
