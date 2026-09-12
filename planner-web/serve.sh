#!/usr/bin/env bash
# Serves the static frontend. Any static server works — this one just avoids
# installing anything. Run `npm run css` first (or `npm run dev` for both).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if [ ! -f assets/css/minima.css ]; then
  echo "No compiled CSS yet — run: npm run css" >&2
  exit 1
fi

PORT="${1:-3000}"
echo "planner-web on http://localhost:$PORT"
echo "  sign in:  http://localhost:$PORT/login.html"
echo "  my trips: http://localhost:$PORT/trips.html"
python3 -m http.server "$PORT"
