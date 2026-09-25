---
name: kill-only-what-you-started
description: never pkill/killall by pattern in this repo; the user's own API (:8080) and static server (:3000) are usually running
metadata:
  type: feedback
---

Stop only the PIDs you started, found by the port you gave them (`lsof -tiTCP:<port> -sTCP:LISTEN`). Never `pkill -f "gradlew.*bootRun"` or similar.

**Why:** on 2026-09-25 a pattern kill used to clean up a verification run on :8081 also ended the user's own API on :8080, which they had running from an earlier session. It could not be restored faithfully because how it was started (env vars, data dir) was unknown.

**How to apply:** before starting anything, check `lsof -nP -iTCP:8080 -iTCP:3000 -sTCP:LISTEN`. If those are taken, run yours on other ports (8081/3001) with its own `DATA_DIR`/`PUBLISH_DIR` and `CORS_ORIGINS`, and on cleanup kill by those ports only. See [[git-workflow-preference]] for the other standing rule about the user's environment.
