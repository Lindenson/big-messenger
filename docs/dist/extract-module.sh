#!/usr/bin/env bash
# =============================================================================
# Extract one messenger-hl module into a standalone repo, PRESERVING history.
#
# Uses git-filter-repo (https://github.com/newren/git-filter-repo) — install:
#   pipx install git-filter-repo    # or: apt/brew install git-filter-repo
#
# It rewrites a fresh clone so that <module>/ becomes the repo root, keeping only
# the commits that touched that path. The source repo is never modified.
#
# Usage:
#   ./extract-module.sh messenger-hl-contracts   ~/repos/messenger-hl-contracts
#   ./extract-module.sh messenger-hl             ~/repos/messenger-hl
#   ./extract-module.sh messenger-hl-redis       ~/repos/messenger-hl-redis
#
# Then, in the OUT dir:
#   git remote add origin git@github.com:OWNER/<module>.git
#   git push -u origin main
# =============================================================================
set -euo pipefail

MODULE="${1:?usage: extract-module.sh <module-subdir> <out-dir>}"
OUT="${2:?usage: extract-module.sh <module-subdir> <out-dir>}"
SRC="$(cd "$(dirname "$0")/../.." && pwd)"   # repo root (docs/dist/ -> ../../)

command -v git-filter-repo >/dev/null 2>&1 || git filter-repo --version >/dev/null 2>&1 || {
  echo "ERROR: git-filter-repo not found. Install it (pipx install git-filter-repo)." >&2; exit 1; }

[ -d "$SRC/$MODULE" ] || { echo "ERROR: $SRC/$MODULE not found" >&2; exit 1; }
[ -e "$OUT" ] && { echo "ERROR: $OUT already exists — refusing to overwrite" >&2; exit 1; }

echo "== fresh mirror clone of $SRC =="
git clone --no-local "$SRC" "$OUT"
cd "$OUT"

echo "== filter to $MODULE/ and lift it to the root, keeping history =="
git filter-repo --force --subdirectory-filter "$MODULE"

echo "== done. $OUT now contains only $MODULE history, at the root =="
echo "   next:"
echo "     cd $OUT"
echo "     # add distribution-management.snippet.xml to pom.xml, publish-on-tag.yml to .github/workflows/"
echo "     git remote add origin git@github.com:OWNER/$MODULE.git"
echo "     git push -u origin main"
echo "     git tag v1.1.0 && git push origin v1.1.0   # triggers the release publish"
