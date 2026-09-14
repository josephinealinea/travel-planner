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

# no-store on everything. Without it python's http.server sends only
# Last-Modified, and browsers then heuristically cache ES modules — so an edited
# js/ file keeps serving its old body while the HTML around it is fresh, and the
# page fails with "X is not defined" for anything newly added. Stylesheets dodge
# this with the ?v= query in each page's <head>; module imports have no such
# escape hatch, so the server has to say it.
python3 -c '
import sys, functools
from http.server import SimpleHTTPRequestHandler, test

class NoCache(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store, must-revalidate")
        super().end_headers()

test(HandlerClass=NoCache, port=int(sys.argv[1]), bind="")
' "$PORT"
