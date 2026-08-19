#!/usr/bin/env bash
# Server-side deploy, run as the forced command of the GitHub Actions SSH key.
#
#   authorized_keys entry:
#   command="/srv/backend/diff-review-service/deploy.sh",restrict ssh-ed25519 ...
#
# The commit SHA to deploy arrives in SSH_ORIGINAL_COMMAND. It is validated as a
# 40-char hex sha and passed to `git reset --hard` - never evaluated as a shell
# command. Any other input is refused, so the key cannot run anything else.
set -euo pipefail

BASE="${DEPLOY_BASE:-/srv/backend/diff-review-service}"
REPO="$BASE/repo"
COMPOSE="$REPO/deploy/docker-compose.yml"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8020/health}"
BRANCH="${DEPLOY_BRANCH:-main}"

log() { printf '[deploy %s] %s\n' "$(date -u '+%H:%M:%S')" "$*"; }

TARGET="${SSH_ORIGINAL_COMMAND:-}"
if [[ -n "$TARGET" && ! "$TARGET" =~ ^[0-9a-f]{40}$ ]]; then
  log "refusing: expected a 40-char commit sha, got something else"
  exit 2
fi

compose_up() {
  docker compose -f "$COMPOSE" up -d --build --remove-orphans
}

wait_healthy() {
  for _ in $(seq 1 40); do
    if curl -fsS --max-time 3 "$HEALTH_URL" >/dev/null 2>&1; then
      return 0
    fi
    sleep 3
  done
  return 1
}

cd "$REPO"
PREV="$(git rev-parse HEAD)"
git fetch --prune --quiet origin "$BRANCH"
git reset --hard --quiet "${TARGET:-origin/$BRANCH}"
log "deploying $(git rev-parse --short HEAD) (previous: ${PREV:0:7})"

compose_up

if wait_healthy; then
  log "healthy: $(curl -fsS --max-time 3 "$HEALTH_URL")"
  # Only this app's own superseded layers, never other projects' images.
  docker image prune -f --filter 'label=app=diff-review-service' >/dev/null || true
  exit 0
fi

log "health check failed after ~120s - rolling back to ${PREV:0:7}"
docker compose -f "$COMPOSE" logs --tail=60 --no-color || true
git reset --hard --quiet "$PREV"
if compose_up && wait_healthy; then
  log "rollback to ${PREV:0:7} is healthy - deploy of the new commit FAILED"
else
  log "rollback ALSO unhealthy - service is DOWN, needs manual attention"
fi
exit 1
