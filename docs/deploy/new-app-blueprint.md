# Blueprint: a second app on this stack

Everything needed to start another app, in another repo, on the same
accounts, the same domain and the same free tiers as travel-planner. It is the
**what to reuse, what to change and what will collide** companion to
[deploy.md](deploy.md), which remains the step-by-step runbook (every command
there works for the new app once its names are swapped in).

Written 1 October 2026. Anything marked *(verify)* is a provider limit that was
true when written and may have moved; check the provider's page before relying
on it.

---

## 1. The stack

| Layer | Choice | Notes |
|---|---|---|
| API | Spring Boot **3.5.3**, Java **21**, Gradle (Kotlin DSL) | Boot 3.5 with Jackson 2 is deliberate, see the root `CLAUDE.md` *Conventions* |
| Persistence | PostgreSQL **17** via plain `JdbcClient`, schema owned by **Flyway** (`db/migration/`, schema only) | No JPA. YAML files locally, Postgres in the cloud, behind `FEATURE_ENABLE_DATABASE` |
| Auth | JWT in an httpOnly cookie plus a double-submit CSRF cookie (`X-XSRF-TOKEN`) | Nimbus, from Spring Security; no third-party JWT library |
| Frontend | Static HTML, **Alpine.js** (vendored), **Sass**; no bundler | Hash-routed tabs, `npm run build` copies to `dist/` |
| Image | `eclipse-temurin:21`, layered jar, class-data-sharing archive, non-root | `planner-api/Dockerfile`; cold start about 7 s |
| API host | **Google Cloud Run**, `europe-west3`, 0 to 1 instances, 1 CPU, 1 GiB | Scales to zero |
| Image registry | **Google Artifact Registry**, Docker repo `planner`, `europe-west3` | Cleanup policy keeps the latest two |
| Database | **Neon** Postgres, `aws-eu-central-1`, direct endpoint, Hikari `minimum-idle: 0` | Scales to zero |
| Website + proxy | **Cloudflare Pages** with two Functions | `/api/*` proxy, `/p/*` from R2 |
| Object storage | **Cloudflare R2** | Only if the new app publishes files |
| Secrets | **Google Secret Manager**; private `.env.*` files on the laptop | Never in git, never in the image |
| Email | Resend over SMTP (`MAIL_MODE=smtp`), or `log` | [deploy.md Part 11](deploy.md#part-11-real-email-resend) |
| DNS / TLS | Registrar Namecheap, **DNS and HTTPS on Cloudflare** | `travellingllama.fun` |
| Tests | JUnit 5, Testcontainers (Postgres, MinIO), `springdoc` for the OpenAPI doc | Pinned to 1.21.4, see below |

Where each is documented in this repo:

| Topic | File |
|---|---|
| Accounts, secrets, deploy, domain, everyday tasks | [deploy.md](deploy.md) |
| Shipping a release (API first, then site) | [ship_to_prod.md](ship_to_prod.md) |
| Each third-party service, with limits and failure behaviour | [`../external-apis/`](../external-apis/README.md) |
| Staying inside the free tiers | [free-tier-usage.md](../external-apis/free-tier-usage.md) |
| Background jobs and scale-to-zero | [`../scheduled/`](../scheduled/) |
| Why each design decision was made | `../../.claude/plans/2026-09-18-postgres-and-cloud-run.md` and the root `CLAUDE.md` |

---

## 2. Decisions for the second app

### 2.1 One domain, two apps: use a subdomain, not a path

Give the new app its own host, for example `app2.travellingllama.fun`, and its
own Cloudflare Pages project. Do **not** serve it under a path such as
`travellingllama.fun/app2`.

Why:

- **Cookies.** Both apps set a cookie named `XSRF-TOKEN` and an auth cookie.
  Host-only cookies are not shared across subdomains, so two subdomains cannot
  overwrite each other's login. On one host they would, and a sign-in to one
  app would sign the other out.
- **Origin.** The auth design needs the website and its API on **one origin**
  (deploy.md, *Why everything sits behind one address*). Each app's Pages
  Function proxies `/api/*` to its own Cloud Run service on its own host.
- **Pages.** One Pages project is one static site with one set of Functions.
  Sharing it would mean path prefixes everywhere (`<base>`, asset URLs, the
  Functions' routes) in an app that currently assumes it sits at `/`.

Setup is the same as deploy.md 8.6, with the subdomain: **Workers & Pages → the
new project → Custom domains → Set up a custom domain**. The zone is already on
Cloudflare, so DNS and the certificate are automatic. No registrar change.

`config.js` defaults the API base to the page's own origin, so a copy of it
needs no change in production.

### 2.2 Neon: a new project, not just a new database

| | New **project** (recommended) | New **database** in `travel-planner` |
|---|---|---|
| Free compute | its own 100 CU-hours / month *(verify)* | shares the 100 with travel-planner |
| Free storage | its own 0.5 GB *(verify)* | shares the 0.5 GB |
| Isolation | separate endpoint, roles, branches | one endpoint, one set of roles |
| Cost to set up | one more connection string | one more `CREATE DATABASE` |
| Free-plan cap | 100 projects per account *(verify)* | n/a |

The free allowances are **per project**, not per account, so a second project
doubles the headroom for nothing. A second database only makes sense if the new
app is tiny and you want one place to look. Today `travel-planner` is at
about 32 MB and uses almost no compute, so either would fit; the project is
still the cleaner choice because a runaway query in one app cannot pause the
other.

Either way, the same rules from deploy.md Part 2 apply: **Postgres 17**, **AWS
Europe Central 1 (Frankfurt)** so it sits beside Cloud Run, the **direct**
endpoint (not `-pooler`, Flyway holds a session lock), `sslmode=require`,
`channel_binding=require` removed, and an **empty** database for Flyway to fill.
Flyway keeps its history table (`flyway_schema_history`) per database, so two
databases in one project never see each other's migrations.

If you do share a project, create a separate **role** and database for the new
app and never reuse `planner_owner`, so a leaked password exposes one app.

### 2.3 Google Cloud: same project, separate names

Reuse project `travellingllama`, its billing account, its €1 budget alert and
region `europe-west3`. The free tiers below are shared by every service in the
project (Cloud Run's is per billing account), so two apps draw on one pool:

| Free allowance | Shared by | Headroom |
|---|---|---|
| Cloud Run 180,000 vCPU-s, 360,000 GiB-s, 2M requests / month | both services | both scale to zero; check before adding a third |
| Artifact Registry 0.5 GB | every repo | about 160 MB per image, so keep the cleanup policy |
| Secret Manager: 6 active secret versions, 10,000 access operations and 3 rotations / month free, then about $0.06 per extra version and $0.03 per 10,000 accesses *(checked against secondary sources 1 Oct 2026; confirm on Google's pricing page)* | the billing account | travel-planner used to hold 9 one-version secrets (3 billable, about $0.18 a month). With one bundle per app, two apps are two versions: free. Reads happen once per cold start, far under 10,000. |

**Artifact Registry.** You said "artifactory registry": the Google product is
**Artifact Registry**, not JFrog Artifactory. Create a **new Docker repo per
app** (for example `app2`) with its own copy of the cleanup policy, rather than
adding another image to `planner`. A "keep the latest two" rule is easy to
misjudge when several images share a repo, and separate repos cannot interfere.
Same location, `europe-west3`. No Docker Hub account is needed.

**One secret per app.** Each app keeps all its secrets in a single Secret
Manager secret, a properties file that Cloud Run mounts and Spring imports
([deploy.md](deploy.md) Part 5). Two apps are two active versions, inside the
free six. The secret's name is **project-wide**, so give the new one its own:
set `SECRETS_BUNDLE='app2-secrets'` in the new app's `.env.deploy`. Copy
`secrets-bundle.sh`, trim its `sources` and `legacy` tables to the keys the app
has (drop `legacy` entirely, there is nothing to migrate), and keep
`SPRING_CONFIG_IMPORT` and the mount in `deploy.sh`. Nothing else about secrets
needs renaming. Also change `SERVICE` (`planner-api` → `app2-api`), the image
path (`.../planner/planner-api` → `.../app2/app2-api`) and `IMAGE_TAG`, which is
per service, so it starts again at `v1`.

`deploy.sh` grants the default compute service account `secretAccessor` on the
**whole project**. That means each Cloud Run service can read the other app's
secrets. For a hobby project that is acceptable; if it is not, create a service
account per app, grant access per secret and pass `--service-account`.

### 2.4 Cloudflare: same account, one more Pages project

| | Notes |
|---|---|
| Pages project | `wrangler pages project create app2 --production-branch=main`. The `*.pages.dev` name is global, so a suffix may be added; ignore it. |
| Pages Functions | The free **100,000 requests / day** is per **account**, shared with travel-planner. Every `/api/*` call is one request. |
| R2 | Only if the app publishes files. Make a **new bucket** (`app2-pages`), and a new API token scoped to that bucket, in the same account. The 10 GB and the operation counts are shared. |
| `wrangler.toml` | Copy it, change `name`, `API_ORIGIN` (the new Cloud Run URL) and the bucket. Secrets (`PROXY_SECRET`) are set with `wrangler pages secret put`, never written in the file. |
| Proxy secret | Generate a **new** one for the new app, so the two Cloud Run services each trust only their own proxy. |

### 2.5 Shared or separate: a quick list

| Thing | Share it? |
|---|---|
| Domain, Cloudflare account, Google project, Neon account | Share |
| Neon project, database, role, JWT secret, proxy secret, CSRF/auth cookies | **Separate** (a shared JWT secret would let a token from one app open the other) |
| Resend account and verified domain | Share. `no-reply@travellingllama.fun` already works; use a different `MAIL_FROM` name if you want one. |
| R2 bucket | Separate bucket, same account |
| Google budget alert | Share (it watches the billing account) |
| User accounts | Separate by default. Sharing users across apps needs one auth service, which this stack does not have. |

---

## 3. What to copy from this repo, and what not to

**Copy as a starting point** (generic, already proven):

| From | What it gives you |
|---|---|
| `planner-api/build.gradle.kts` | The dependency set, the Testcontainers pin, the `-parameters` flag, the `openApiUpdate` task |
| `planner-api/Dockerfile`, `.dockerignore` | The layered, CDS-trained, non-root image. **`.dockerignore` is a security list**: it keeps `data/` and `.env.*` out of the image. |
| `planner-api/deploy.sh` | Build, push, deploy, then check health and that direct access is refused. Rename the secrets and service first (2.3). |
| `config/DatabaseModeEnvironment`, `META-INF/spring.factories` | Lets the app start with no database when the flag is off. Written with a test (`YamlModeStartupTest`) first. |
| `config/ProxySecretFilter`, `FilterErrors`, `CsrfFilter`, `JwtCookieAuthFilter`, `SecurityConfig` | The cookie/CSRF/JWT auth and the "locked `run.app` door" |
| `resilience/CircuitBreaker`, `shared/HttpCallLog` | Only if the new app calls metered APIs |
| `application.yml` | Pool settings, graceful shutdown, `forward-headers-strategy`, health-only actuator |
| `planner-web/functions/api/[[path]].js`, `wrangler.toml`, `_headers`, `404.html` | The proxy, the Pages settings, the no-cache headers |
| `planner-web/js/boot.js`, `api.js`, `config.js`, `dialog.js`, `i18n/` | Boot order, the fetch wrapper with CSRF, the API-base logic, focus-trapped dialogs, localisation |
| `compose.yaml` | Local Postgres 17 |
| Root `CLAUDE.md` *Traps* section | Each entry cost a debugging cycle and applies to any app on this stack |

**Leave behind** (travel-specific): the trip, checklist, itinerary, budget,
weather, flights, publish and settlement modules, `PlanTemplates`, the
country table, and every external API except what the new app needs.

If you would rather not copy and diverge, the generic parts (auth, config,
storage base classes, the `deploy.sh` pattern) are good candidates for a
shared starter repo later. That is a bigger decision than this document makes.

---

## 4. Collisions and gotchas, in the order you will hit them

| Where | Problem | Fix |
|---|---|---|
| **Local ports** | The travel-planner API uses `:8080` and the site `:3000`; running both apps at once collides | Give the new app `PORT=8081` and serve its site on `:3001`, with `CORS_ORIGINS=http://localhost:3001` |
| **Local cookies** | Browsers do **not** separate cookies by port, so two apps on `localhost` share one cookie jar. Same cookie names mean one app logs the other out. | Give the new app **different cookie names** (change `AuthCookies`, the session cookie `tp_session`, the CSRF name and the `js/api.js` read) before you run both |
| **Local `localStorage`** | Also shared across ports on the same host. `config.js` stores the API base under `plannerApiBase`. | Use a different key in the new app, and see the root `CLAUDE.md` on clearing it |
| **Secret name** | Project-wide (2.3) | `SECRETS_BUNDLE='app2-secrets'` |
| **Cloud Run service name** | Unique per project and region | `app2-api` |
| **`API_ORIGIN`** | The Cloud Run URL is only known after the first deploy | Deploy the API, read the URL, then put it in `wrangler.toml` |
| **Proxy secret** | A mismatch gives `403 proxy_required` on every call | Pipe it from Secret Manager into Pages, as in deploy.md 8.4 |
| **Neon connection** | The pooler host breaks Flyway; `channel_binding=require` breaks the Java driver | See 2.2 |
| **First account** | There is no sign-up page by design, so a fresh database has nobody | deploy.md Part 9 |
| **Docker on Colima** | Testcontainers skips the Postgres tests when it cannot find Docker, and the build still says green | Both `~/.testcontainers.properties` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` are already set on this Mac; always count `skipped` |
| **Docker architecture** | The Mac is Intel, the same as Cloud Run | On Apple Silicon add `--platform linux/amd64` |
| **Scale to zero** | First request after a quiet spell takes 7 to 8 s; scheduled jobs may never fire | Refresh on read, as in `../scheduled/exchange-rates-refresh.md` |
| **Cloud Run disk** | In-memory and discarded on every restart | No `MAIL_MODE=file`, no generated JWT secret, no local YAML data in the cloud |
| **One instance only** | YAML locks and some read-modify-write sequences are process-local | Keep `--max-instances=1` |

---

## 5. Order of work for the new app

Each step points at the part of [deploy.md](deploy.md) that has the commands.
Accounts and the domain already exist, so Part 1 is skipped.

1. **Create the repo and the skeleton**: copy the generic files in section 3,
   rename packages, cookies, ports and the `plannerApiBase` key.
2. **Neon**: create the project (2.2) and write `.env.neon`. (Part 2)
3. **Local run** in YAML mode, then in database mode against local Postgres.
   Run `./gradlew test` and check `skipped` is 0.
4. **Google**: `gcloud config` already points at `travellingllama`. Create the
   Docker repo `app2` and its cleanup policy. (Parts 3 and 6)
5. **Secrets**: fill the `.env.*` files and run `./secrets-bundle.sh --apply`. (Part 5)
6. **Deploy the API** with the edited `deploy.sh`; confirm health `UP` and the
   direct-access `403`. (Part 7)
7. **Cloudflare**: create the Pages project, set `API_ORIGIN`, set the proxy
   secret, deploy, attach the subdomain. (Part 8, and 2.1 here)
8. **First account** and, if needed, real email. (Parts 9 and 11)
9. **Usage**: add the new app's services to the free-tier checks (section 6).

---

## 6. Free-tier tracking is still travel-planner only

The `free-tier-usage` skill and `usage-report.sh` (in `~/.claude/skills/`, so
not in this repo) and [free-tier-usage.md](../external-apis/free-tier-usage.md)
are written for **one** Cloud Run service, **one** Neon project and **one**
Pages project. After adding the new app they will report only the first app's
figures for Neon and Cloud Run, while Artifact Registry, Pages Functions and R2
(account-wide) will already include both. Extend them when the second app is
live, or you will read a safe-looking number that leaves out half the load.

---

## 7. Conventions to carry into the new repo

These come from the global and project `CLAUDE.md`. Put the same rules in the
new repo's `CLAUDE.md` so a fresh session follows them:

- **API docs**: generate the OpenAPI file from the controllers (springdoc, test
  scope), commit it, and fail a test when it is stale.
- **External services**: one `docs/external-apis/<service>.md` each (what, auth,
  base URL and config key, each call with trigger and frequency, caching,
  failure behaviour, quirks) and a row in the overview table.
- **Scheduled jobs**: one `docs/scheduled/<job>.md` each, including the config
  key that changes the schedule and how it behaves on scale-to-zero.
- **README commands**: each command under its own `####` heading in its own
  fenced block.
- **Settings and secrets**: tunables are plain values in `application.yml`; only
  keys and per-environment values are environment variables; each service's
  secret lives in its own git-ignored `.env.*` file with `chmod 600`.
- **New settings** go in their own `@ConfigurationProperties` record, never into
  a shared one that tests construct positionally.
- **Money and data safety**: a free-tier overrun that **bills** (Cloud Run,
  Artifact Registry, R2) is the thing to watch, and the €1 budget alert is the
  net.
