#!/usr/bin/env bash
# Starts the local Postgres used by database mode (FEATURE_ENABLE_DATABASE=true).
# Safe to run again — `up -d` reuses the existing container and volume rather
# than recreating them, so your data survives.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

docker compose up -d postgres

echo -n "Waiting for Postgres to be ready"
for _ in $(seq 1 30); do
  if docker compose exec -T postgres pg_isready -U planner -d planner >/dev/null 2>&1; then
    echo " — ready on localhost:5432"
    exit 0
  fi
  echo -n "."
  sleep 1
done

echo
echo "Postgres did not report ready in time — check: docker compose logs postgres" >&2
exit 1
