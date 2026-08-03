#!/usr/bin/env bash
# =============================================================================
# Bootstrap the messenger-hl modules into the local Maven repo (~/.m2).
#
# The high-performance modules live in their own repos and are consumed as
# Maven artifacts. Until they are published to GitHub Packages, a fresh checkout
# has nothing to resolve them from — run this once (and after a module changes)
# so `mvn package` on the app can build.
#
#   ./scripts/bootstrap-modules.sh        # clone/update + install all three
#   mvn -DskipTests package               # then build the app
#
# Config (env vars; defaults in []):
#   OWNER    [Lindenson]                GitHub owner of the module repos
#   WORKDIR  [../hl-modules]            where the module checkouts live
#   REF      [main]                     branch/tag to build
#   MVN      [./mvnw or mvn]            maven command
#   WITH_TESTS [false]                  run module tests during install
#
# Requires: git (with credentials for the private repos, e.g. `gh auth setup-git`).
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

OWNER="${OWNER:-Lindenson}"
WORKDIR="${WORKDIR:-../hl-modules}"
REF="${REF:-main}"
if [ -x ./mvnw ]; then MVN="${MVN:-./mvnw}"; else MVN="${MVN:-mvn}"; fi
WITH_TESTS="${WITH_TESTS:-false}"

# Build order matters: contracts is the base; hl and redis depend on it.
MODULES=(messenger-hl-contracts messenger-hl messenger-hl-redis)

MVN_FLAGS="-q -Djacoco.skip=true"
[ "$WITH_TESTS" = "true" ] || MVN_FLAGS="$MVN_FLAGS -DskipTests"

mkdir -p "$WORKDIR"
for m in "${MODULES[@]}"; do
  dir="$WORKDIR/$m"
  if [ -d "$dir/.git" ]; then
    echo "== update $m =="
    git -C "$dir" fetch -q origin "$REF"
    git -C "$dir" checkout -q "$REF"
    git -C "$dir" reset -q --hard "origin/$REF"
  else
    echo "== clone $m =="
    git clone -q --branch "$REF" "https://github.com/$OWNER/$m.git" "$dir"
  fi
  echo "== install $m -> ~/.m2 =="
  # shellcheck disable=SC2086
  "$MVN" $MVN_FLAGS -f "$dir/pom.xml" clean install
done

echo "OK: all messenger-hl modules installed to ~/.m2. Now: $MVN -DskipTests package"
