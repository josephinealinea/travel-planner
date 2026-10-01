# Neon Postgres

Database mode only (`FEATURE_ENABLE_DATABASE=true`). Direct endpoint (not the
pooler), Hikari `minimum-idle: 0` so an idle app lets Neon sleep. Flyway runs
at startup. See `../deploy/deploy.md`.

The free allowances (100 CU-hours, 0.5 GB, 5 GB transfer) are **per Neon
project**, so a second app should get its own project; see
[new-app-blueprint.md](../deploy/new-app-blueprint.md#22-neon-a-new-project-not-just-a-new-database).


Back to the [overview](README.md).
