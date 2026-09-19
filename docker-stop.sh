#!/usr/bin/env bash
# Stops the local Postgres container without deleting it or its data —
# `docker-start.sh` picks up right where this left off. To wipe the data too,
# run `docker compose down --volumes` instead.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

docker compose stop postgres
