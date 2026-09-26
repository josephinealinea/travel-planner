# Neon Postgres

Database mode only (`FEATURE_ENABLE_DATABASE=true`). Direct endpoint (not the
pooler), Hikari `minimum-idle: 0` so an idle app lets Neon sleep. Flyway runs
at startup. See `../deploy/deploy.md`.


Back to the [overview](README.md).
