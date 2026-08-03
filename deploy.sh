#!/usr/bin/env bash
# =============================================================================
# CI/CD deploy — Hormiga Messenger service
#
# Builds the Quarkus fast-jar, bundles it, ships it to the deploy host, unpacks
# it into the hormiga-deploy checkout (03-services/<service>/quarkus-app) and
# restarts the container so the new build goes live. No manual server steps.
#
# The target layout matches https://github.com/Lindenson/hormiga-deploy :
#   03-services/docker-compose.yml mounts ./<service>:/app and runs
#   /app/quarkus-app/quarkus-run.jar — so the deployable bundle is the fast-jar
#   directory `target/quarkus-app`, placed at 03-services/<service>/quarkus-app.
#
# Usage:
#   DEPLOY_HOST=deploy@preprod.example.com ./deploy.sh
#
# Configuration (env vars; defaults in []):
#   SERVICE        [messenger]              service name / deploy sub-dir / container suffix
#   DEPLOY_HOST    (required)            ssh target, e.g. deploy@preprod.example.com
#   DEPLOY_PATH    [hormiga-deploy]      hormiga-deploy checkout path on the server
#   REMOTE_DOCKER  [sudo docker]         docker command on the server
#   EDGE           [https://hormi.isolutions.io]  public edge, for the health check
#   SKIP_TESTS     [true]               skip unit tests during the deploy build
#   SSH_OPTS       []                    extra ssh/scp options (identity file, port…)
#   MVN            [./mvnw or mvn]       maven command
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")"

SERVICE="${SERVICE:-messenger}"
CONTAINER="hormiga-${SERVICE}-app"
: "${DEPLOY_HOST:?set DEPLOY_HOST=user@host}"
DEPLOY_PATH="${DEPLOY_PATH:-hormiga-deploy}"
REMOTE_DOCKER="${REMOTE_DOCKER:-sudo docker}"
EDGE="${EDGE:-https://hormi.isolutions.io}"
SKIP_TESTS="${SKIP_TESTS:-true}"
SSH_OPTS="${SSH_OPTS:-}"
if [ -x ./mvnw ]; then MVN="${MVN:-./mvnw}"; else MVN="${MVN:-mvn}"; fi

BUNDLE="target/${SERVICE}-quarkus-app.tgz"
REMOTE_TMP="/tmp/${SERVICE}-quarkus-app.$$.tgz"

echo "== [1/5] build fast-jar ($SERVICE) =="
# The messenger-hl modules now live in their own repos and are consumed as published Maven artifacts
# (GitHub Packages; locally resolved from ~/.m2). The app build no longer builds them from source.
if [ "$SKIP_TESTS" = "true" ]; then
  "$MVN" -q -DskipTests clean package
else
  "$MVN" -q clean package
fi
[ -f target/quarkus-app/quarkus-run.jar ] \
  || { echo "ERROR: target/quarkus-app/quarkus-run.jar not found (need quarkus.package.jar.type=fast-jar)"; exit 1; }

echo "== [2/5] bundle -> $BUNDLE =="
tar czf "$BUNDLE" -C target quarkus-app

echo "== [3/5] ship to $DEPLOY_HOST =="
# shellcheck disable=SC2086
scp $SSH_OPTS "$BUNDLE" "$DEPLOY_HOST:$REMOTE_TMP"

echo "== [4/5] unpack into $DEPLOY_PATH/03-services/$SERVICE and restart $CONTAINER =="
# Local values are interpolated into the here-doc; remote-only vars are escaped (\$DEST).
# shellcheck disable=SC2086
ssh $SSH_OPTS "$DEPLOY_HOST" bash -s <<REMOTE
set -euo pipefail
DEST="$DEPLOY_PATH/03-services/$SERVICE"
mkdir -p "\$DEST"
rm -rf "\$DEST/quarkus-app.new"; mkdir -p "\$DEST/quarkus-app.new"
tar xzf "$REMOTE_TMP" -C "\$DEST/quarkus-app.new"          # -> quarkus-app.new/quarkus-app
rm -rf "\$DEST/quarkus-app.old"
[ -d "\$DEST/quarkus-app" ] && mv "\$DEST/quarkus-app" "\$DEST/quarkus-app.old"
mv "\$DEST/quarkus-app.new/quarkus-app" "\$DEST/quarkus-app"
rm -rf "\$DEST/quarkus-app.new" "$REMOTE_TMP"
echo "  restarting $CONTAINER (mounted jar reloaded on restart)"
$REMOTE_DOCKER restart "$CONTAINER" >/dev/null
REMOTE

echo "== [5/5] verify $CONTAINER is running (stable, not crash-looping) =="
# NOTE: an edge probe is useless — Oathkeeper 401s anonymous /$SERVICE/** BEFORE the
# app, so 401 doesn't prove liveness. And these apps log the Quarkus banner to a file
# handler, not stdout, so "started in" isn't in `docker logs`. Assert container state
# instead: Running && !Restarting, stable across several checks (crash-loop -> not stable).
# shellcheck disable=SC2086
ssh $SSH_OPTS "$DEPLOY_HOST" bash -s <<REMOTE
set -e
stable=0
for i in \$(seq 1 30); do
  st=\$($REMOTE_DOCKER inspect -f '{{.State.Running}}:{{.State.Restarting}}' $CONTAINER 2>/dev/null || echo none)
  if [ "\$st" = "true:false" ]; then
    stable=\$((stable+1))
    if [ \$stable -ge 4 ]; then echo "OK: $CONTAINER running and stable"; exit 0; fi
  else
    stable=0
  fi
  sleep 2
done
echo "WARN: $CONTAINER not stable (last state: \$st); recent logs:"
$REMOTE_DOCKER logs --tail 25 $CONTAINER 2>&1 || true
exit 1
REMOTE
