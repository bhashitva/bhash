#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Install frontend tracking on the pilot without updating its compiler image.
set -euo pipefail
stack=${1:?Pass the existing stack directory}
release=${2:?Pass an extracted, built release directory}
cd "$stack"
test -f compose.yaml
test -s "$release/playground/dist/bhash-analytics.js"
if test -e compose.override.yaml; then
  echo 'An override already exists. Review and merge it before proceeding.' >&2
  exit 1
fi
backup="$stack/analytics-backup-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$backup"
cp deploy/playground/Caddyfile "$backup/Caddyfile"
cp compose.yaml "$backup/compose.yaml"
mkdir -p analytics-web
cp -a "$release/playground/dist/." analytics-web/
cp "$release/deploy/analytics/Caddyfile.live" analytics-live.Caddyfile
cat > compose.override.yaml <<'YAML'
services:
  service:
    volumes:
      - ./analytics-web:/opt/bhash/web:ro
  gateway:
    volumes:
      - ./analytics-live.Caddyfile:/etc/caddy/Caddyfile:ro
YAML
docker compose config --quiet
docker compose run --rm --no-deps gateway caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
docker compose up -d --no-build
printf 'Frontend analytics installed. Backup: %s\n' "$backup"
