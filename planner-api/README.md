# planner-api

Spring Boot 3.5 / Java 21. Feature-module layout, and no database — see the
[root README](../README.md) for the product behaviour.

## Layout

Each feature module keeps the same four layers:

```
<module>/domain/   the stored records
<module>/api/      services — where the rules live
<module>/infra/    repository interface + its YAML implementation
<module>/web/      controllers and request/response types
```

```
config/         AppProperties, FeatureFlags, SecurityConfig, JwtService,
                JwtCookieAuthFilter, CsrfFilter, PasswordChangeGate, BootstrapOwner
shared/         ApiException, GlobalExceptionHandler, Slugs, Ids, Nights
storage/        YamlStore, YamlPaths, TripLocks, TripScopedYamlRepository
identity/       accounts, sign-in, the forced password change
trips/          the trip, its members, and every permission decision
destinations/   places, and the checklist seeding that follows adding one
checklist/      the free-text checklist and its status
itinerary/      plans, their pre-filled templates, and the budget cascade
budget/         expenses and the display-currency rollup
geocoding/      the countries.dev client
notification/   EmailSender and the message templates
publish/        publish requests, and rendering the public page
```

## Configuration

Everything has a working default; these are the ones worth knowing.

| Property | Default | Notes |
|---|---|---|
| `feature-enable-database` | `false` | On means "use a database", which is not implemented — startup fails with an explanation rather than doing nothing |
| `app.storage.root` | `./data` | Where the YAML files live |
| `app.publish.dir` | `./data/published` | Rendered public pages |
| `app.publish.public-base-url` | `http://localhost:8080/p` | Used to build the link shown in the app |
| `app.mail.mode` | `file` | `file` writes `.eml` to `data/outbox`, `log` logs it, `smtp` sends it |
| `app.security.jwt-secret` | generated | Persisted to `data/secret.yml` so restarting does not sign everybody out. Set it explicitly before deploying |
| `app.cors.allowed-origins` | `http://localhost:3000` | Cookie auth needs the frontend origin listed |
| `app.bootstrap.owner-email` | — | Seeds the first account, since there is no self-signup |

## Private settings files (`.env.*`)

Secrets and account identifiers never go in git. They live in one file per
service, **in this folder** (`planner-api/`). Every `.env.*` file is git-ignored
(`planner-api/.gitignore`) and left out of the Docker image (`.dockerignore`).
Create each one private from the start (`chmod 600`, so only you can read it),
put one `KEY='value'` per line, and never paste a key into a chat or a command
line.

| File | Holds | Needed for | Where it comes from |
|---|---|---|---|
| `.env.local` | Bootstrap owner, storage mode, mail settings | Running the API on your machine | Written by hand, contents below (there is no template file) |
| `.env.neon` | `DB_URL`, `DB_USER`, `DB_PASSWORD` | Deploying, and the one-off data import | Neon, [deploy.md](../docs/deploy/deploy.md) Part 2 |
| `.env.r2` | `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` | Deploying (published pages in R2) | Cloudflare R2, deploy.md Part 4 |
| `.env.deploy` | Project, region, domain, image tag, app settings (no secrets) | `./deploy.sh` | Written by hand, deploy.md Part 7 |
| `.env.resend` | `RESEND_API_KEY` | Real email: locally, and to store the key in Secret Manager | Resend, deploy.md Part 11 |
| `.env.aerodatabox` | `AERODATABOX_KEY` (your RapidAPI key) | Flight lookup: schedule, airports, status | RapidAPI, [aerodatabox.md](../docs/external-apis/aerodatabox.md) |
| `.env.aviationstack` | `AVIATIONSTACK_KEY` | Flight lookup: codeshares, gate, baggage, delay | AviationStack, [aviationstack.md](../docs/external-apis/aviationstack.md) |

Only `.env.local` is needed to run the API on your machine. The two flight-key
files are optional: without a key that service is simply off and lookups answer
"unavailable". Tunables (limits, TTLs, the circuit breaker) are **not** in these
files; they are plain values in `src/main/resources/application.yml`.

### Create each file

Each file is created the same way, then opened to fill in. Replace the name.

#### Create a private file and open it (example: `.env.neon`)
```bash
cd planner-api && touch .env.neon && chmod 600 .env.neon && open -e .env.neon
```

Then paste the contents shown below and save.

#### `.env.local` contents
```
BOOTSTRAP_OWNER_EMAIL=you@example.com
BOOTSTRAP_OWNER_PASSWORD=password123
FEATURE_ENABLE_DATABASE=true
MAIL_MODE=smtp
SMTP_HOST=smtp.resend.com
SMTP_PORT=465
SMTP_SSL=true
SMTP_AUTH=true
SMTP_USER=resend
SMTP_PASSWORD="$RESEND_API_KEY"
MAIL_FROM=no-reply@travellingllama.fun
```

This is the one file for running the API on your machine, and it is created the
same way as the others, with the command above (`touch .env.local && chmod 600
.env.local && open -e .env.local`). The bootstrap owner is the first account,
since there is no self-signup; it is only created if the account does not exist
yet, so an existing account keeps its own password. `FEATURE_ENABLE_DATABASE=true`
uses the local Postgres (start it first with `./docker-start.sh`), and `false`
uses the YAML files under `data/`. **The two stores are separate: trips made in
one are not visible in the other.** For quiet local testing without real email,
set `MAIL_MODE=log`.

`SMTP_PASSWORD="$RESEND_API_KEY"` is written with double quotes on purpose, and
reads `.env.resend`, so that file has to be loaded **before** `.env.local` (see
below).

#### `.env.neon` contents
```
DB_URL='jdbc:postgresql://<host>/planner?sslmode=require'
DB_USER='<role>'
DB_PASSWORD='<password>'
```

Use Neon's **direct** connection string (the host without `-pooler`), converted
to the `jdbc:postgresql://` form with the user and password kept separate, and
without `&channel_binding=require`. Details: deploy.md Part 2.

#### `.env.r2` contents
```
R2_ACCOUNT_ID='<32 hex characters>'
R2_ACCESS_KEY_ID='<32 hex characters>'
R2_SECRET_ACCESS_KEY='<64 hex characters>'
```

The account ID is your Cloudflare account's ID, not the bucket name. The access
keys are shown once, when the API token is created. Details: deploy.md Part 4.

#### `.env.deploy` contents
```
PROJECT_ID='travellingllama'
REGION='europe-west3'
SERVICE='planner-api'
IMAGE_TAG='v1'                      # bump for every new build
DOMAIN='travellingllama.fun'
FEATURE_ENABLE_DATABASE='true'
PUBLISH_STORE='r2'
R2_BUCKET='travel-planner-pages'
MAIL_MODE='log'                     # 'smtp' for real email (then also SMTP_HOST, SMTP_PORT, SMTP_USER, MAIL_FROM)
```

No secrets here. Every deploy replaces the service's whole environment with
exactly what this file (plus the secrets in Secret Manager) provides, so a setting
changed only in the Cloud Run console is undone by the next deploy. Details:
deploy.md Part 7 and Part 11.

#### `.env.resend` contents
```
RESEND_API_KEY='re_...'
```

#### `.env.aerodatabox` contents
```
AERODATABOX_KEY='<your RapidAPI key>'
```

#### `.env.aviationstack` contents
```
AVIATIONSTACK_KEY='<your AviationStack key>'
```

### Use them

A file is loaded for one command with `set -a; . ./.env.x; set +a`, which makes
its values available to that command without printing them.

#### Run the API locally, with real email and the flight keys
```bash
cd planner-api && (set -a; . ./.env.resend; . ./.env.local; . ./.env.aerodatabox; . ./.env.aviationstack; set +a; ./gradlew bootRun)
```

Source `.env.resend` **before** `.env.local`, because `SMTP_PASSWORD="$RESEND_API_KEY"`
is expanded at the moment `.env.local` is read. A file that does not exist only
prints an error and the run carries on without it. More run variants (database
mode, no email) are in the [root README](../README.md).

#### Store a key in Secret Manager for Cloud Run (example: AeroDataBox)
```bash
cd planner-api && (set -a; . ./.env.aerodatabox; set +a; printf '%s' "$AERODATABOX_KEY" | gcloud secrets create aerodatabox-key --data-file=- --replication-policy=automatic --project=travellingllama)
```

The same pattern stores the others (`aviationstack-key`, `db-password`,
`r2-secret-access-key`, `smtp-password`) and rotating a key is a
`gcloud secrets versions add ...` from the same file. `./deploy.sh` attaches the
secrets that exist. The full list and the rotation commands are in
[deploy.md](../docs/deploy/deploy.md).

## Auth

A JWT in an httpOnly cookie (`tp_session`), so no token is ever readable from
script, plus a double-submit CSRF cookie echoed back as `X-XSRF-TOKEN`. The user
is re-read from storage on every request, so a screen-name change takes effect
immediately rather than at the next sign-in.

While `mustChangePassword` is set, `PasswordChangeGate` answers **409
`password_change_required`** to everything except `/auth/me`, `/auth/logout` and
`/account/password`. The frontend turns that into a redirect.

## The countries.dev client

Three behaviours of that API are load-bearing and all confirmed against it live:

1. `/cities` answers **404 with the plain text `No cities found`** when nothing
   matches. That is an empty result, not a failure.
2. `/cities` has a population floor. Uyuni (10,293) and Ollantaytambo (2,000) are
   absent from it but present in `/places` — and small towns are exactly what a
   trip is made of, so both are queried and merged on `geonameId`.
3. `/places` is the full gazetteer, so it also returns administrative regions,
   hotels and rivers. Only `featureClass` `"P"` is kept.

Some places have no entry under the name travellers use at all — Peru's Aguas
Calientes is registered as *Machupicchu* — so an empty result is normal and
coordinates can always be typed in by hand.

## Endpoints

All under `/api/v1`, all cookie-authenticated, except `/p/{slug}` which is public.

```
POST   /auth/login                              GET  /auth/me
POST   /auth/logout                             GET  /auth/csrf
POST   /account/password                        PATCH /account/profile

GET    /trips                                   POST   /trips
GET    /trips/{id}          the whole workspace in one response
PATCH  /trips/{id}                              DELETE /trips/{id}          owner
GET    /trips/{id}/members                      POST   /trips/{id}/members
DELETE /trips/{id}/members/{userId}

GET    /geocode?q=cusco
GET    /trips/{id}/destinations                 POST   /trips/{id}/destinations
PATCH  /trips/{id}/destinations/{destId}        DELETE /trips/{id}/destinations/{destId}
POST   /trips/{id}/destinations/reorder

GET    /trips/{id}/checklist                    POST   /trips/{id}/checklist
PATCH  /trips/{id}/checklist/{itemId}           DELETE /trips/{id}/checklist/{itemId}
PATCH  /trips/{id}/checklist/{itemId}/status
GET    /trips/{id}/checklist/{itemId}/plan-template

GET    /trips/{id}/itinerary                    POST   /trips/{id}/itinerary
PATCH  /trips/{id}/itinerary/{planId}           DELETE /trips/{id}/itinerary/{planId}

GET    /trips/{id}/budget                       POST   /trips/{id}/budget
PATCH  /trips/{id}/budget/{itemId}              DELETE /trips/{id}/budget/{itemId}

POST   /trips/{id}/publish                 owner        DELETE /trips/{id}/publish   owner
POST   /trips/{id}/publish-requests        member        GET    /trips/{id}/publish-requests
POST   /trips/{id}/publish-requests/{reqId}/approve      owner
POST   /trips/{id}/publish-requests/{reqId}/reject       owner
POST   /trips/{id}/publish-requests/{reqId}/cancel       requester

GET    /p/{slug}                           public, no auth
```

Failures are RFC-7807 `ProblemDetail` with a machine-readable `code`, plus an
`errors` field-to-message map on validation failures.

#### Run the tests
```bash
./gradlew test
```
#### Start it with a first account
```bash
BOOTSTRAP_OWNER_EMAIL=you@example.com BOOTSTRAP_OWNER_PASSWORD=password123 ./gradlew bootRun
```
#### Start it on a different port
```bash
PORT=8080 PUBLIC_BASE_URL=http://localhost:8080/p ./gradlew bootRun
```
#### Start fresh
```bash
rm -rf data && ./gradlew bootRun
```

## Notes for whoever works on this next

- **The nights rule** lives in `shared/Nights`. Both dates are optional, and it
  returns null whenever the count is not calculable, which is what makes the
  seeded item fall back from "Plan 6N accommodation in Cusco" to "Plan
  accommodation in Cusco".
- **The budget cascade** is `itinerary/api/BudgetSync`, deliberately one-way and
  narrow: a later cost change updates only the amount and currency, so a
  description somebody has since corrected in the budget is never clobbered.
- **Adding a plan never completes a checklist item.** Only an explicit status
  call does, which is what "Plan another" is for.
- **Derived values are not stored.** Nights are recomputed from the dates rather
  than written into the trip files, where they could go stale.
- **The published page is assembled by placeholder substitution**, not
  `String.format` — the template contains percent-encoded text of its own.
