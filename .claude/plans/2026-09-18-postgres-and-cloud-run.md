# Plan — PostgreSQL, and a free-tier deployment on Cloud Run + Neon + Cloudflare

**Status:** **All three phases done** (19 Sep). Phases 1–2 committed
(`b2223d9`); Phase 3 (importer, CLAUDE.md, brief, docs) in the working tree,
uncommitted. 672 tests green, none skipped. The importer was rehearsed on a copy
of the real data and verified end to end — see the brief. Next: deploying,
per `../../docs/deploy/deploy.md`, by the 4 Oct milestone.
Found during Phase 1: Testcontainers must be 1.21.4+ for this machine's
Docker 29 (pinned in `build.gradle.kts`), and under Colima the container tests
are skipped rather than failed unless Testcontainers is told where Docker is —
now set once in `~/.testcontainers.properties` + `~/.zshrc` (`../../docs/deploy/deploy.md`,
*Local development*).
**Date:** 2026-09-18
**Revised:** same day. v1 assumed the frontend inside the container and pages
on a mounted Cloud Storage bucket. v2 targets free tiers: **Cloud Run** (API),
**Neon** (Postgres), **Cloudflare** (DNS, frontend, published pages), on your
own domain. See §2 for what changed and why.

> **Before anything runs: please commit the current working tree.** Parallel
> agents work in git worktrees created from `HEAD`. The settle-expenses work is
> uncommitted, so every agent would start without it and merging back would
> conflict. This is the only thing that needs you before execution starts.

---

## 1. What you are approving

1. **Database mode.** `feature-enable-database=true` puts every record in
   PostgreSQL. With the flag off the app behaves exactly as today, byte for
   byte — local development keeps using YAML.
2. **Flyway owns the schema, and only the schema.** Migrations never insert,
   update or delete rows.
3. **A one-off import** of your existing YAML data into Postgres — application
   code, *not* a Flyway migration (§5.10). Recommended because of the trip
   date (§3); say if you'd rather re-enter the data by hand.
4. **Published pages move to Cloudflare R2**, served at the edge without waking
   the API.
5. **The frontend moves to Cloudflare Pages**, on the same domain as the API.
6. **An API-only Docker image** for Cloud Run.
7. **Exchange rates refresh when stale** instead of on a schedule.
8. **Local Postgres** via Docker Compose.

Deploying itself is not in the build scope; §9 is the runbook for when you do.

---

## 2. Target architecture

```
yourdomain.com  (Cloudflare DNS + TLS)                          free
 ├─ /*       Cloudflare Pages → the planner's static files      free, unmetered
 ├─ /api/*   Pages Function → proxy → Cloud Run                 free: 100k req/day
 └─ /p/*     Pages Function → reads R2 directly                 free: 10 GB, no egress fees
                                  (Cloud Run is never woken for a public page)

Cloud Run  europe-west3 (Frankfurt) · API only · 0–1 instances  free tier
 └─ Neon Postgres  aws-eu-central-1 (Frankfurt)                 free: 0.5 GB, 100 CU-hours
```

**Why this shape** — each piece answers a specific constraint:

| Constraint | How it's met |
|---|---|
| Auth cookies are `SameSite=Lax` and the CSRF token is read from `document.cookie` by JavaScript, with host-only cookies | Everything is served on **one origin**. The browser only ever sees `yourdomain.com`. No auth code changes. |
| Cloud Run's disk is temporary | Records → Neon; pages → R2; JWT secret → Secret Manager |
| Cloud Storage FUSE **refuses directory renames** on ordinary buckets by default, and approving a publish request renames a directory | R2 behind a small storage interface instead of a mounted bucket. *(v1 said "zero code change" — that was wrong.)* |
| Scale-to-zero means cold starts of several seconds | Static files and public pages never touch Cloud Run; only signed-in API calls can hit a cold start |
| Free-tier Cloud Run has CPU only during a request | No in-process scheduling is relied on (§5.7) |
| A shared trip link opened by a stranger | Served from R2 at the edge — instant, even when the API is asleep |

**What changed from v1:** D2 (frontend no longer in the container — same
*origin* is what matters, not same container), D3 (R2, not a mounted bucket),
D4 (stale-on-read, no scheduler), D5 (import now recommended), plus region.

---

## 3. Your situation: Tallinn, Singapore, and South America next month

**Region: Frankfurt for both Cloud Run and Neon.**

| Region pair | Tallinn | Singapore | South America |
|---|---|---|---|
| **Frankfurt** | ~30 ms | ~160 ms | ~200–220 ms |
| N. Virginia | ~120 ms | ~230 ms | ~110–130 ms |
| Singapore | ~180 ms | ~5 ms | ~300 ms+ |

*(Rough round-trip times from each place to that region.)*

- **You do most of the planning — every write — and keep using it after the
  trip.** Frankfurt is fastest for the heaviest user.
- **~160–220 ms per action is fine for a planning app**, and each screen is one
  request (`GET /trips/{id}` returns everything). A cold start costs seconds, so
  it dwarfs the difference between regions anyway.
- **On the road, people mostly *read*.** The published page is served from
  Cloudflare's edge near wherever you are, with no API involved.
- **Cloud Run and Neon must be in the same city.** A trip loads with about eight
  queries. At 1–2 ms apart that's nothing; at 80 ms apart it's over half a
  second per screen.
- Frankfurt is a Tier 2 Cloud Run region. That only affects **paid** rates; the
  free tier applies in every region.
- **Time zones are already safe.** Itinerary times are stored as wall-clock time
  at the destination, the browser formats every timestamp in the viewer's own
  zone, and the server code uses `Clock.systemUTC()` explicitly. Moving from
  Tallinn time (local) to UTC (Cloud Run) changes nothing.

**The trip date is the real constraint.** Your trip runs **24 Oct – 8 Nov**, so
there are about five weeks, and today the app only runs on your laptop — your
friends can't reach it at all. Two things follow:

1. **Your trip data has to come with you.** A fresh database is empty, so
   without the importer (§5.10) you would re-enter every destination, plan and
   expense by hand. The importer is now in scope, *unless you tell me
   otherwise*. It still respects your "Flyway: schema only" rule.
2. **Deploy early, then freeze.** Suggested milestones are in §8: a working
   deploy with your data by **4 Oct**, two weeks of real use with your friends,
   and **no deploys after 17 Oct** except fixes.

---

## 4. What I found in the code

All verified, not assumed.

| # | Finding | Consequence |
|---|---|---|
| F1 | **Only 2 of 8 repositories are interfaces** (users, trips). The other six are concrete YAML classes that services inject directly — CLAUDE.md's "a database implementation drops in without touching a service" is only true for two. | Turn each into an interface **with the same name**, and rename the YAML class. **No service changes**, only 62 mechanical renames in 18 test files. |
| F2 | Per-trip repositories are keyed by **trip slug**. Slugs are set once and never change. | Tables key on `trip_id` and resolve the slug in SQL; repository signatures unchanged. |
| F3 | Budget and weather have no `sortOrder`; they tie-break on YAML list order. `replaceAll` relies on list order too. | Every per-trip table gets a `seq` insertion-order column. |
| F4 | **Member order changes money.** Leftover cents go to the earliest sharers, and an empty "Shared by" resolves to members *in list order*. | `trip_members.position`; `shared_by_user_ids` as ordered `text[]`. |
| F5 | Itinerary `startAt`/`endAt` are wall-clock `LocalDateTime`. | `timestamp` **without** time zone, or a 06:00 departure shifts. |
| F6 | Weather ids (`"lat,lon:date"`) repeat across trips. | Primary keys are `(trip_id, id)`. |
| F7 | The trip repository stamps both timestamps on every save; the user repository normalises emails. | The JDBC versions do the same, pinned by contract tests. |
| F8 | **No test boots the Spring context.** | A YAML-mode startup test is written *before* the database dependency is added. |
| F9 | The JWT secret self-generates into `data/secret.yml`. | On Cloud Run that means everyone is logged out on every restart → `JWT_SECRET` from Secret Manager. |
| F10 | Approving a publish request **renames a directory**; the publish code touches the filesystem in exactly three classes (`StaticSiteRenderer`, `PublicPageController`, `TripViewAssembler`). | A contained storage interface (§5.6). |
| F11 | `TripViewAssembler` checks for a personal page **once per member on every trip load**. | On R2 that would be one network call per member; the interface lists a trip's personal pages in **one** call instead. |
| F12 | A published page is ~290 KB (272 KB `index.html` + 17 KB `trip.json`), plus the same per personal page. | R2's 10 GB holds tens of thousands. |
| F13 | The server code uses `Clock.systemUTC()` explicitly. | Running in UTC changes no behaviour (§3). |
| F14 | Docker is installed and running here. | Testcontainers and Compose both work locally. |

---

## 5. Design

### 5.1 Switching modes without breaking YAML mode

Adding `spring-boot-starter-jdbc` makes Spring Boot build a `DataSource` on
every start — which fails with "url not specified" in YAML mode.

- **`DatabaseModeEnvironment`**, an `EnvironmentPostProcessor` registered in
  `META-INF/spring.factories`. When the flag is off it adds the DataSource,
  transaction-manager, JdbcTemplate/JdbcClient, SQL-init and Flyway
  auto-configurations to `spring.autoconfigure.exclude`, merged with any
  existing excludes. YAML mode then starts exactly as today.
- Every JDBC repository is `@ConditionalOnProperty(name =
  "feature-enable-database", havingValue = "true")`, mirroring the annotation
  already on every YAML one.
- **`FeatureFlags`** stops throwing and logs which store is active.

### 5.2 Repository interfaces (F1)

```
storage/TripScopedRepository<T>      new interface: findAll, findById, save,
                                     saveAll, delete, replaceAll
storage/TripScopedYamlRepository<T>  unchanged logic, now implements it

budget/infra/BudgetRepository        becomes an interface (same name)
budget/infra/YamlBudgetRepository    the current class, renamed
budget/infra/JdbcBudgetRepository    new
… same for checklist, destinations, itinerary, weather, rates
```

### 5.3 Schema — `V1__baseline_schema.sql` (draft)

Rules:
- Nullable unless structurally required. YAML lets any field be absent, and
  null already means something in places (`allDay`, `lodgingSeeded`).
- Primitive booleans → `NOT NULL DEFAULT false`.
- **Foreign keys only where the app guarantees the target exists:** `trip_id →
  trips ON DELETE CASCADE`, owner and members → users. Cross-row links
  (itinerary↔budget, checklist↔itinerary, `seededFromDestinationId`, payer,
  sharers, audit ids) get none, because the app deliberately keeps them after
  their target is gone ("Former member", unlinking on destination delete,
  `adoptOrphanedDays`).
- Enums as `text`, no `CHECK` — a check would add a fifth place to edit when
  adding a category.
- The cascade makes "deleting a trip deletes everything it put anywhere" a
  property of the schema. Removing the published page stays in
  `TripService.delete`.

```sql
CREATE TABLE users (
    id                        text PRIMARY KEY,
    email                     text NOT NULL UNIQUE,   -- stored normalised, as YAML does
    screen_name               text,
    password_hash             text,
    must_change_password      boolean NOT NULL DEFAULT false,
    currencies                text[]  NOT NULL DEFAULT '{}',
    display_currency          text,
    publish_itinerary_cost    boolean NOT NULL DEFAULT false,
    publish_destination_days  boolean NOT NULL DEFAULT false,
    publish_forecast_expenses boolean NOT NULL DEFAULT false,
    publish_personal_budget   boolean NOT NULL DEFAULT false,
    created_at                timestamptz,
    updated_at                timestamptz
);

CREATE TABLE trips (
    id                 text PRIMARY KEY,
    slug               text NOT NULL UNIQUE,
    title              text,
    start_date         date,
    end_date           date,
    owner_user_id      text REFERENCES users (id),
    status             text NOT NULL DEFAULT 'DRAFT',
    display_currency   text,
    published_theme    text,
    published_at       timestamptz,
    created_at         timestamptz,
    updated_at         timestamptz,
    created_by_user_id text,
    updated_by_user_id text
    -- Trip.exchangeRates deliberately absent: "no longer read", kept only so
    -- old YAML loads. A database has no old files.
);

CREATE TABLE trip_members (
    trip_id            text    NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    user_id            text    NOT NULL REFERENCES users (id),
    position           integer NOT NULL,   -- F4: decides who gets the odd cent
    email              text,
    role               text    NOT NULL DEFAULT 'MEMBER',
    invited_by_user_id text,
    invited_at         timestamptz,
    PRIMARY KEY (trip_id, user_id)
);
CREATE INDEX trip_members_user_id_idx ON trip_members (user_id);

CREATE TABLE publish_requests (
    trip_id              text    NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    id                   text    NOT NULL,
    position             integer NOT NULL,
    requested_by_user_id text,
    status               text    NOT NULL DEFAULT 'PENDING',
    note                 text,
    theme                text,
    requested_at         timestamptz,
    decided_at           timestamptz,
    decided_by_user_id   text,
    PRIMARY KEY (trip_id, id)
);

CREATE TABLE destinations (
    trip_id            text   NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    id                 text   NOT NULL,
    seq                bigint GENERATED ALWAYS AS IDENTITY,   -- F3
    name               text,
    country_code       text,
    country_name       text,
    country_flag       text,
    latitude           double precision,
    longitude          double precision,
    geoname_id         bigint,
    timezone           text,
    start_date         date,
    end_date           date,
    notes              text,
    sort_order         integer NOT NULL DEFAULT 0,
    lodging_seeded     boolean,
    suppress_checklist boolean,
    created_at         timestamptz,
    created_by_user_id text,
    updated_at         timestamptz,
    updated_by_user_id text,
    PRIMARY KEY (trip_id, id)
);

CREATE TABLE checklist_items (
    trip_id                    text    NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    id                         text    NOT NULL,
    seq                        bigint  GENERATED ALWAYS AS IDENTITY,
    country_codes              text[]  NOT NULL DEFAULT '{}',
    seeded_from_destination_id text,
    category                   text    NOT NULL DEFAULT 'OTHERS',
    description                text,
    note                       text,
    status                     text    NOT NULL DEFAULT 'TODO',
    auto_seeded                boolean NOT NULL DEFAULT false,
    sort_order                 integer NOT NULL DEFAULT 0,
    completed_at               timestamptz,
    created_at                 timestamptz,
    created_by_user_id         text,
    updated_at                 timestamptz,
    updated_by_user_id         text,
    PRIMARY KEY (trip_id, id)
);

CREATE TABLE itinerary_items (
    trip_id            text   NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    id                 text   NOT NULL,
    seq                bigint GENERATED ALWAYS AS IDENTITY,
    checklist_item_id  text,
    plan_id            text,
    category           text   NOT NULL DEFAULT 'OTHERS',
    description        text,
    start_at           timestamp,          -- F5: wall-clock at the destination
    end_at             timestamp,
    all_day            boolean,
    cost               numeric,
    currency           text,
    budget_item_id     text,
    country_codes      text[] NOT NULL DEFAULT '{}',
    sort_order         integer NOT NULL DEFAULT 0,
    created_at         timestamptz,
    created_by_user_id text,
    updated_at         timestamptz,
    updated_by_user_id text,
    PRIMARY KEY (trip_id, id)
);

CREATE TABLE budget_items (
    trip_id            text   NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    id                 text   NOT NULL,
    seq                bigint GENERATED ALWAYS AS IDENTITY,  -- F3: budget's only tie-break
    itinerary_item_id  text,
    category           text   NOT NULL DEFAULT 'OTHERS',
    description        text,
    amount             numeric,
    currency           text,
    date               date,
    country_codes      text[] NOT NULL DEFAULT '{}',
    shared_by_user_ids text[] NOT NULL DEFAULT '{}',        -- F4: order matters
    paid_by_user_id    text,
    status             text   NOT NULL DEFAULT 'CONFIRMED', -- no status reads as a charge
    confirmed_at       timestamptz,
    created_at         timestamptz,
    created_by_user_id text,
    updated_at         timestamptz,
    updated_by_user_id text,
    PRIMARY KEY (trip_id, id)
);

CREATE TABLE weather_records (
    trip_id         text   NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    id              text   NOT NULL,          -- "lat,lon:date" — F6
    seq             bigint GENERATED ALWAYS AS IDENTITY,
    latitude        double precision NOT NULL,
    longitude       double precision NOT NULL,
    date            date   NOT NULL,
    weather_code    integer,
    temperature_max double precision,
    temperature_min double precision,
    precipitation   double precision,
    source          text,
    fetched_at      timestamptz,
    PRIMARY KEY (trip_id, id)
);

CREATE TABLE rate_table (
    singleton  boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    base       text NOT NULL,
    date       text,
    fetched_at timestamptz
);

CREATE TABLE exchange_rates (
    currency text    PRIMARY KEY,
    position integer NOT NULL,
    rate     numeric NOT NULL
);
```

No extra indexes: the `(trip_id, id)` primary key serves every per-trip load.
Records are tiny against Neon's 0.5 GB.

### 5.4 How the JDBC repositories behave

| YAML behaviour | JDBC equivalent |
|---|---|
| `findAll` in file order | `ORDER BY seq` |
| `save` replaces in place, else appends | `INSERT … ON CONFLICT (trip_id, id) DO UPDATE`, `seq` untouched |
| `saveAll` — one write | same, in one transaction |
| `replaceAll` — the list becomes exactly this | one transaction: delete the trip's rows, insert in the given order |
| `delete` | `DELETE … WHERE trip_id = … AND id = …` |
| slug-keyed | `trip_id = (SELECT id FROM trips WHERE slug = :slug)` — never the entity's own `tripId` |
| `TripLocks` | not needed: row upserts can't clobber each other the way two rewrites of one file can |
| Trip save stamps both timestamps | same, in the repository |
| Trip save rewrites members / requests | upsert the trip, delete-and-reinsert children with `position`, one transaction |
| Trip delete removes five files | one `DELETE FROM trips`; the cascade does the rest |
| User emails normalised in the repo | same normalisation, same place |
| Legacy upconversion (`destinationIds` → `countryCodes`) | YAML only |

**Domain classes are not touched.** YAML mode must write byte-identical files.

### 5.5 Neon configuration

```yaml
feature-enable-database: ${FEATURE_ENABLE_DATABASE:false}

spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/planner}
    username: ${DB_USER:planner}
    password: ${DB_PASSWORD:planner}          # local-dev default, matches compose
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:5}
      minimum-idle: 0                         # an idle pool must not keep Neon awake
      idle-timeout: 30000                     # ms — Hikari's setters take a long, not "30s"
      connection-timeout: 30000               # ms — room for Neon to wake from sleep
  flyway:
    locations: classpath:db/migration

server:
  shutdown: graceful                          # Cloud Run's SIGTERM drains requests
```

- **`sslmode=require`** in Neon's URL.
- **The direct endpoint, not `-pooler`.** Flyway holds a session-level lock that
  Neon's transaction-mode pooler cannot. With at most one Cloud Run instance and
  five connections, pooling isn't needed.
- **`minimum-idle: 0`** matters for the free tier. Neon sleeps after 5 minutes
  idle and gives 100 CU-hours a month; a pool that holds idle connections open
  would stop it sleeping and spend those hours while nobody is using the app.

### 5.6 Published pages behind a `PageStore` (F10, F11)

```
publish/infra/PageStore              interface
publish/infra/FileSystemPageStore    today's behaviour, moved — used locally
publish/infra/R2PageStore            Cloudflare R2 over its S3-compatible API
```

Chosen by `app.publish.store: filesystem | r2`, **independent of the database
flag**, so local development keeps writing files.

- **Operations are the ones the code already does:** write a file, read one,
  delete a tree, promote pending → published, and **list a trip's personal
  pages in one call** (F11 — replaces a per-member existence check on every
  trip load).
- **Keys:** `published/<slug>/…` and `pending/<slug>/…`, in **one private
  bucket**. Only the Cloudflare Function decides what is public, and it only
  ever reads `published/`. The members-only preview still goes through the API.
- **Approve** becomes copy-then-delete. Today's safe ordering is kept: the old
  live page goes first, so a failure leaves no page rather than a half-merged
  one.
- **Client:** AWS SDK v2 S3 with the lightweight URL-connection HTTP client,
  **created lazily on first use** so the SDK adds nothing to a cold start that
  doesn't publish.
- **Three classes change:** `StaticSiteRenderer`, `PublicPageController`,
  `TripViewAssembler`. `PublicPageController` keeps serving locally and stays
  the reference for what the Function must do.

**The Pages Function for `/p/*`** mirrors `PublicPageController` exactly: the
same four routes, `index.html` for `/p/<slug>` with or without the trailing
slash, `trip.json`, member pages, 404 otherwise, `Cache-Control: public,
max-age=300`. It validates path segments the way `Slugs.requireSafe` does, and
caches at the edge.

### 5.7 Exchange rates: refresh when stale

On free-tier Cloud Run the CPU is only there during a request, so neither the
01:30 cron nor the startup fetch's background thread can be relied on. Instead
`RatesService` checks the table's age when it's read: **older than 24 h →
refresh**, with the existing 10 s timeout, one refresh at a time, and the
existing rule that a failed refresh keeps the old table. No Cloud Scheduler, no
Worker cron. The cron stays for always-on and local runs, where it's harmless.

### 5.8 The frontend on Cloudflare Pages

- **Build:** `npm ci && npm run build` in `planner-web/`. A new `build` script
  runs `npm run css` and copies the site into `dist/`, so `node_modules/` and
  `scss/` are never uploaded.
- **`dist/_headers`** sets `Cache-Control: no-cache` on HTML, `js/` and `css/`
  (browsers revalidate — they keep the file but check it's current). This
  defuses the stale-ES-module trap in CLAUDE.md for good, rather than depending
  on Pages' defaults.
- **`functions/api/[[path]].js`** — the proxy, about 20 lines:
  - forwards the method, headers, body and query to `API_ORIGIN` (the
    `run.app` URL), which also sets the Host header Cloud Run routes on;
  - adds `X-Forwarded-Host` / `X-Forwarded-Proto` and a shared-secret header;
  - passes `Set-Cookie` straight back.
- **`functions/p/[[path]].js`** — §5.6.
- **`config.js`** defaults to the page's own origin, except on
  `localhost:3000`, where it keeps using `localhost:8080`. **Local development
  is unchanged.**
- **The API checks the shared secret** (`PROXY_SECRET`, set only in the cloud)
  and rejects anything without it, apart from `/actuator/health`. Otherwise the
  public `run.app` URL would be a second front door around Cloudflare.
- **Limits:** only `/api/*` and `/p/*` run a Function and count toward
  100k/day; static files are unmetered. Proxying is waiting, not CPU, so the
  10 ms CPU cap per request isn't an issue.

### 5.9 The image — API only

`planner-api/Dockerfile`, two stages:

1. **`eclipse-temurin:21-jdk`** — Gradle `bootJar -x test`, with dependencies
   in their own layer. Selects the boot jar explicitly, not Gradle's
   `-plain.jar`. Extracts it with Boot's `jarmode=tools` into layers, then does
   a **class-data-sharing training run** (starts the context in YAML mode and
   exits at refresh) to shorten cold starts.
2. **`eclipse-temurin:21-jre`** — non-root user, layered copy, the CDS archive,
   `JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`.
   The port comes from `$PORT`, as it already does.

Tests are skipped in the image build — they need Docker, which a build doesn't
have — and run before it instead.

**`.dockerignore` is a security item.** `planner-api/data/` holds `users.yml`
(password hashes), `secret.yml` (the JWT signing key) and every trip; none of
it may enter the build context. Also excluded: `build/`, `.gradle/`, `.idea/`.

Cold starts are measured with and without the CDS archive, and the numbers go in
the brief.

### 5.10 The one-off YAML → Postgres import

- **Application code, not a migration.** An `ApplicationRunner` that only runs
  when `app.import.yaml-dir` is set, in database mode. Flyway stays schema-only.
- **It reads through the YAML repositories and writes through the JDBC ones**,
  so legacy fields are upconverted on the way. It moves users (password hashes
  included, so everyone's login keeps working), then trips with members and
  requests, then each trip's destinations, checklist, itinerary and budget.
  Weather is skipped (it's a cache) and so are rates (refetched).
- **List order is preserved** by writing each trip's rows with `replaceAll` in
  file order, which keeps F3's tie-breaks.
- **Refuses to run on a non-empty database**, and has a `--dry-run` that prints
  counts.
- **It proves itself:** for every trip and every member, it compares the budget
  summary (totals, shares, settlements) computed from YAML with the one from
  Postgres, and fails loudly on any difference.
- **You run it from your laptop against Neon**, where the YAML files live. Then
  publish once to render the pages into R2.

### 5.11 New configuration lives in new classes

`AppProperties` and its nested `Publish` record are constructed **positionally
in 20 test files each**. Adding a single component to either would break all
20 — and would do it in several agents' branches at once. So every new setting
goes in its own `@ConfigurationProperties` record, and `AppProperties` is not
touched:

| Record | Keys | Used by |
|---|---|---|
| `PageStoreProperties` | `app.publish.store`, `app.r2.account-id`, `bucket`, `access-key-id`, `secret-access-key` | Agent D |
| `ProxyProperties` | `app.proxy.secret` | Agent C |
| `ImportProperties` | `app.import.yaml-dir`, `app.import.dry-run` | Agent E |

**Agent 1 writes all of these keys into `application.yml` up front**, from this
table, so it remains the only agent that edits that file.

---

## 6. Testing

- **Contract tests — the core proof.** For each repository interface, one
  abstract contract test holding every behaviour in §5.4. Two subclasses run it:
  **YAML** (temp directory, always) and **Postgres** (Testcontainers,
  Flyway-migrated, skipped automatically on a machine without Docker). If the two
  ever disagree, a test fails.
- **`PageStore` contract tests** — filesystem always; R2 against a local
  S3-compatible container (MinIO) in Testcontainers.
- **Startup tests in both modes** (F8).
- **The existing 276 tests stay on YAML** and must stay green, with only the
  mechanical renames.
- **Schema-only guard:** no `INSERT`/`UPDATE`/`DELETE` under `db/migration/`.
- **Functions:** checked locally with `wrangler pages dev`, proxying to a local
  API and reading from Miniflare's local R2: sign in, edit, publish, open
  `/p/<slug>`.
- **Importer:** run against a copy of your real `data/` into a Testcontainers
  database; its own totals comparison must pass.
- **Image:** builds, runs against Compose Postgres with the flag on, health UP,
  no `data/` inside, measured cold start.

---

## 7. Execution — who does what, in parallel

Each file belongs to exactly one agent.

### Phase 0 — you
- [ ] Commit the current working tree.

**Shared files, and who owns them:** `application.yml` → Agent 1.
`build.gradle.kts` → Agent 1, plus Agent D's dependency lines merged by me.
`SecurityConfig` → Agent C. `AppProperties` → nobody (§5.11). Every other file
belongs to exactly one agent.

### Phase 1 — three agents in parallel

**Agent 1 · Database foundations** (Phase 2 waits on this)
- `build.gradle.kts` dependencies: JDBC starter, Flyway core + Postgres module,
  Postgres driver; test: Boot Testcontainers support, Testcontainers Postgres
  and JUnit.
- YAML-mode startup test **first**, then the dependency (F8).
- `DatabaseModeEnvironment`, `FeatureFlags`, application.yml — §5.5 **plus
  every key in §5.11** that Agents C, D and E consume. It is the only agent that
  edits that file.
- Interface extraction (F1) and the 62 renames.
- `V1__baseline_schema.sql`.
- Shared JDBC scaffolding in `storage/jdbc/`: the per-trip base implementing
  §5.4 once, array helpers, the Testcontainers test base.
- `compose.yaml` with Postgres 17.

**Agent C · Image and Cloudflare frontend** (no files in common with Agents 1 or D)
- `planner-api/Dockerfile` with CDS, `.dockerignore`.
- `planner-web`: `build` script, `_headers`, `functions/api/[[path]].js`,
  `config.js` same-origin default.
- The proxy shared-secret filter in the API, `ProxyProperties`, and its
  registration in `SecurityConfig` (C is the only agent touching it).
- `../../docs/deploy/deploy.md` — the runbook in §9.

**Agent D · Published pages on R2**
- `PageStore`, `FileSystemPageStore`, `R2PageStore`, the three call sites
  (F10, F11), `PageStoreProperties`, `functions/p/[[path]].js`, contract tests
  with MinIO.
- **The one shared file:** Agent D needs the S3 SDK in `build.gradle.kts`,
  which Agent 1 is also changing. D adds its dependency lines in its own branch
  and I merge that one file by hand — a few lines, in a `dependencies` block.

### Phase 2 — after Agent 1 merges

**Agent A · identity, trips, rates** — JDBC user, trip and rates repositories,
their contract tests, and the stale-on-read refresh (§5.7).

**Agent B · the five per-trip repositories** — destinations, checklist,
itinerary, budget, weather, each a thin subclass of the Phase 1 base, plus
their contract tests.

### Phase 3 — after A and B merge

**Agent E · Importer** (§5.10), since it needs both implementations.

**Me:** merge; full suite in both modes; image against Compose Postgres; one
end-to-end run in the browser through `wrangler pages dev`; importer against a
copy of your data; CLAUDE.md (correct F1's claim, and document database mode,
the schema rules, `PageStore` and the deployment shape); brief in
`.claude/briefs/`; README commands. **Nothing committed** — handed back to you.

Relative size: Agent 1 **L**, C **M**, D **M**, A **M**, B **M**, E **S–M**.

---

## 8. Timeline against the trip (24 Oct)

| By | Milestone |
|---|---|
| 21 Sep | You commit; Phase 1 starts (Agents 1, C, D) |
| 27 Sep | Phase 2 done (A, B); importer started |
| **4 Oct** | **First deploy**: Neon + R2 + Pages + Cloud Run set up, your data imported, pages published, you signed in on your domain |
| 5–16 Oct | Your friends in Singapore sign in and actually use it; fix what real use finds |
| **17 Oct** | **Freeze.** Nothing new is deployed during the trip, fixes only. Take a `pg_dump` of Neon to your laptop before you leave. |

If 4 Oct slips badly, the fallback costs nothing: YAML mode on your laptop
exactly as today, plus the published pages. Nothing in this plan makes the
current setup worse.

---

## 9. Deploy runbook (documented in `../../docs/deploy/deploy.md`, not executed)

**Once, in order:** Neon project (Frankfurt, Postgres 17) → R2 bucket (location
hint Western Europe) and an API token → Secret Manager: `JWT_SECRET`,
`DB_PASSWORD`, `R2_SECRET_ACCESS_KEY`, `PROXY_SECRET` → Artifact Registry repo
**with a cleanup policy keeping the last two images** → Cloud Run service → the
Pages project on your domain, bound to the R2 bucket, with `API_ORIGIN` and
`PROXY_SECRET` → run the importer from your laptop → publish.

| Cloud Run setting | Value | Why |
|---|---|---|
| region | `europe-west3` | Same city as Neon (§3) |
| `FEATURE_ENABLE_DATABASE` | `true` | |
| `DB_URL` | Neon *direct* URL, `sslmode=require` | §5.5 |
| `DB_USER` / `DB_PASSWORD` | Neon role / secret | |
| `JWT_SECRET` | secret | F9 |
| `COOKIE_SECURE` | `true` | |
| `PUBLIC_BASE_URL` | `https://yourdomain.com/p` | The links the Publish tab hands out |
| `CORS_ORIGINS` | `https://yourdomain.com` | Same origin, so unused, but explicit |
| `PUBLISH_STORE` + `R2_*` | `r2`, account, bucket, key id, secret | §5.6 |
| `PROXY_SECRET` | secret | §5.8 |
| `MAIL_MODE` + `SMTP_*` | any free SMTP, or `log` | File mode writes to a temporary disk |
| `BOOTSTRAP_OWNER_*` | not needed | The importer brings your account |
| min / max instances | 0 / 1 | Free tier; one instance is ample |
| `--cpu-boost` | on | Faster JVM start |
| billing | request-based | Always-on leaves the free tier |
| memory | 1 GiB | |

**Free-tier guardrails:** a **$1 budget alert** on the billing account (Cloud
Run needs one even at $0); the Artifact Registry cleanup policy (0.5 GB free);
Hikari `minimum-idle: 0` (Neon CU-hours); max one instance; only `/api` and `/p`
count toward Cloudflare's 100k/day.

Local commands, in the README's per-command format:

#### Start local Postgres
```bash
docker compose up -d postgres
```
#### Run the API in database mode
```bash
cd planner-api && FEATURE_ENABLE_DATABASE=true BOOTSTRAP_OWNER_EMAIL=you@example.com BOOTSTRAP_OWNER_PASSWORD=password123 ./gradlew bootRun
```
#### Build the API image
```bash
docker build -t planner-api planner-api
```
#### Run the frontend through the Cloudflare proxy locally
```bash
cd planner-web && npm run build && npx wrangler pages dev dist
```
#### Dry-run the import into local Postgres
```bash
cd planner-api && FEATURE_ENABLE_DATABASE=true ./gradlew bootRun --args='--app.import.yaml-dir=./data --app.import.dry-run=true'
```

---

## 10. Risks

| Risk | Mitigation |
|---|---|
| YAML and Postgres behave differently and nobody notices | Contract tests run the same assertions against both |
| YAML mode breaks from the new dependency unnoticed (F8) | Startup test written before the dependency |
| Money moves because order is lost (F4) | `position` / ordered `text[]`, pinned with a three-way split; the importer's totals comparison |
| Itinerary times shift a time zone (F5) | `timestamp` without zone, pinned by a 06:00 round trip |
| Secrets baked into the image | `.dockerignore`; Phase 3 inspects the built image |
| The Function serves something that isn't public | It only ever reads `published/`; segments validated like `Slugs.requireSafe` |
| The `run.app` URL is used to go around Cloudflare | Shared-secret filter |
| Neon's free compute runs out | `minimum-idle: 0`, so it sleeps when idle |
| The deploy isn't ready before the trip | §8 milestones and freeze; the laptop setup stays as a fallback |
| Parallel agents start without your uncommitted work | Phase 0 |

---

## 11. Out of scope

Creating the cloud resources (§9 documents them); CI; service-level
`@Transactional`; multi-instance safety beyond what database mode gives for
free; importing weather or rates; an offline mode for the planner.

---

## 12. Done means

- [ ] Flag off: behaviour and files identical to today; 276 existing tests green
- [ ] Flag on: boots against Compose Postgres, Flyway applies `V1`, and trips,
      destinations, checklist, itinerary, budget (including Settle Expenses),
      weather and rates all work in the browser
- [ ] Every repository contract test passes against both YAML and Postgres;
      every `PageStore` contract test against both filesystem and MinIO
- [ ] No `INSERT`/`UPDATE`/`DELETE` under `db/migration/`
- [ ] No service class changed for database mode
- [ ] A trip load lists personal pages with one storage call, not one per member
- [ ] Through `wrangler pages dev`: sign in, edit, publish, approve, and open
      `/p/<slug>` served from R2 without the API being involved
- [ ] Rates refresh on read once older than 24 h; a failed refresh keeps the table
- [ ] Image builds, runs as non-root, contains no `data/`; health UP; cold start
      measured with and without CDS
- [ ] The importer copies your real data with its totals comparison passing, and
      refuses a non-empty database
- [ ] `../../docs/deploy/deploy.md` covers every setting and secret in §9
- [ ] CLAUDE.md updated; brief written
