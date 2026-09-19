# Hosting travel-planner: Cloudflare, Cloud Run and Neon

How the app went live at **https://travellingllama.fun**, in the order it was
done, from creating the accounts to the first sign-in. It doubles as the
runbook for rebuilding it from scratch and for everyday redeploys.

It was first set up on **19 September 2026**. The reasoning behind each design
decision is in `.claude/plans/2026-09-18-postgres-and-cloud-run.md`, and
[free-tier-usage.md](free-tier-usage.md) covers keeping it free.

---

## The big picture

Three services, each doing one job, all on a free plan:

| Service | What it does here | Where |
|---|---|---|
| **Cloudflare** | Owns the domain's DNS and HTTPS, hosts the website (Pages), forwards API calls (a Pages Function), stores published trip pages (R2) | Worldwide; R2 in Western Europe |
| **Google Cloud Run** | Runs the API, the Spring Boot app, as a container that sleeps when nobody uses it | Frankfurt (`europe-west3`) |
| **Neon** | The Postgres database | Frankfurt (`aws-eu-central-1`) |

```
travellingllama.fun  (Cloudflare DNS + HTTPS)
 ├─ /*       Cloudflare Pages      → the website (planner-web/dist)
 ├─ /api/*   Pages Function proxy  → Cloud Run (the API)  → Neon (the database)
 └─ /p/*     Pages Function        → R2 bucket (published trip pages)
```

**Why everything sits behind one address.** Sign-in uses cookies that only
work when the website and the API share one origin. The `/api` proxy makes the
browser see a single site, `travellingllama.fun`, even though the API really
lives on Google. The proxy also adds a secret header, and the API refuses any
request without it. So the API's own `run.app` address is a locked door, not a
second way in.

**Why Frankfurt twice.** Opening a trip takes about eight database queries, so
the distance between the API and the database is paid eight times per screen.
Putting both in the same city keeps that near zero.

---

## Part 1: Accounts and tools

### 1.1 The domain: Namecheap → Cloudflare

`travellingllama.fun` was registered at **Namecheap**. Namecheap stays the
registrar (it's where the domain is renewed), but **Cloudflare runs its DNS**,
which is what lets Cloudflare serve the site, issue the HTTPS certificate and
attach the domain to Pages with one click.

1. Create a Cloudflare account at [dash.cloudflare.com](https://dash.cloudflare.com)
   and choose the **Free** plan.
2. **Add a domain** → `travellingllama.fun`. Cloudflare scans the existing DNS
   records and gives you **two nameservers**. For this domain they are
   `dorthy.ns.cloudflare.com` and `kanye.ns.cloudflare.com`.
3. In **Namecheap → Domain List → Manage → Nameservers**, choose **Custom DNS**
   and enter those two nameservers.
4. Wait. The change spreads through the internet over a few hours, and
   Cloudflare emails you when the domain is **Active**.

Check progress from the terminal. It's done when both answers show the
Cloudflare nameservers:

#### Ask Cloudflare's resolver which nameservers the domain uses
```bash
dig +short NS travellingllama.fun @1.1.1.1
```
#### Ask Google's resolver the same
```bash
dig +short NS travellingllama.fun @8.8.8.8
```

### 1.2 Neon

Create an account at [console.neon.tech](https://console.neon.tech). The free
plan needs no card. The project itself is created in Part 2.

Neon's onboarding offers a setup prompt (`neon login`, `neon link`,
`neon deploy`…). That's for apps built *on* Neon's own functions and storage.
It isn't needed here and was skipped: this app only uses Neon as a Postgres
database.

### 1.3 Google Cloud

1. Create an account at [console.cloud.google.com](https://console.cloud.google.com).
2. Create a project. Its ID is **`travellingllama`**.
3. **Billing → Link a billing account.** Cloud Run needs a card on file even
   when everything stays inside the free tier.
4. **Billing → Budgets & alerts → Create budget**: name `travel-planner`,
   amount **€1**, alerts at **50 %, 90 % and 100 %**. This is the safety net:
   any unexpected charge emails you at once rather than at the end of the
   month. An alert only notifies; it doesn't stop anything.

### 1.4 Tools on the Mac

| Tool | Used for | How it was installed |
|---|---|---|
| `gcloud` | Everything Google | `brew install --cask google-cloud-sdk` |
| Docker (via Colima) | Building the API image | Already set up for local development |
| Node.js / npm | Building the website, and running `wrangler` | Already installed |
| `wrangler` | Everything Cloudflare | Not installed. `npx wrangler@4` downloads and runs it each time |
| `neon` CLI | Reading Neon usage (optional) | Already installed |

The Mac is Intel (`x86_64`), the same CPU type Cloud Run uses, so the image
built locally runs there unchanged. On an Apple Silicon Mac, `docker build`
would need `--platform linux/amd64`.

Claude Code has two plugins that can read these accounts directly: the
**Cloudflare** plugin (enabled in this project's `.claude/settings.json`, and
signed in through a browser the first time) and the **Neon** MCP server. They
were used to check each step, but nothing here depends on them.

### 1.5 The private files

Secrets and account identifiers never go in git. They live in three files in
`planner-api/`, all covered by `.env.*` in `.gitignore` and `.dockerignore`,
and all `chmod 600` (only you can read them):

| File | Holds | Written in |
|---|---|---|
| `.env.neon` | `DB_URL`, `DB_USER`, `DB_PASSWORD` | Part 2 |
| `.env.r2` | `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` | Part 4 |
| `.env.deploy` | Project, region, domain, image tag, app settings (no secrets) | Part 7 |

Values are written in single quotes, one per line, like `DB_USER='planner_owner'`.
A command loads a file with `set -a && . ./.env.neon && set +a`, which makes
its values available to that command without printing them. Passwords were
never pasted into a chat or typed on a command line, so none of them appear in
shell history.

If you lose these files, everything in them can be recovered: the Neon values
from the Neon console, the R2 key by creating a new token, and the secrets
from Google Secret Manager.

---

## Part 2: The database (Neon)

In the Neon console, **Create project**:

| Setting | Value | Why |
|---|---|---|
| Name | `travel-planner` | |
| Region | **AWS Europe Central 1 (Frankfurt)** | Same city as Cloud Run |
| Postgres version | **17** | What the local `compose.yaml` and every test use |
| Database name | `planner` | |

> **Postgres 17, not 18.** The project was first created on Neon's default,
> 18, and then recreated on 17. The app, its migrations and its tests have only
> ever run on 17, and the migration tool (Flyway) predates 18.

Then, from **Connect** (connection details), with the database set to `planner`:

- Copy the **direct** connection string, **not** the one whose host contains
  `-pooler`. When the API starts, Flyway holds a lock for the whole session,
  which Neon's pooler can't keep.
- Remove `&channel_binding=require` from the end. That option exists only in
  `psql`'s own driver (libpq), not in Java's.

Turn the string into the three lines of `planner-api/.env.neon`:

```
DB_URL='jdbc:postgresql://<host>/planner?sslmode=require'
DB_USER='<role>'
DB_PASSWORD='<password>'
```

The host and role come from the connection string. Java needs the
`jdbc:postgresql://` form, with the user and password kept separate rather
than inside the URL.

#### Create the file, private from the start
```bash
cd planner-api && touch .env.neon && chmod 600 .env.neon && open -e .env.neon
```

Leave the database **empty**. The API creates its own tables (Flyway, schema
version 1) the first time it starts.

---

## Part 3: Point `gcloud` at the project

#### Sign in to Google (opens a browser)
```bash
gcloud auth login
```
#### Make travellingllama the default project
```bash
gcloud config set project travellingllama
```
#### Check billing is linked (should print True)
```bash
gcloud billing projects describe travellingllama --format='value(billingEnabled)'
```

---

## Part 4: Storage for published pages (Cloudflare R2)

A published trip is a single web page. The app writes those pages into an R2
bucket, and the `/p/*` Function reads them back, so a reader opening a
published trip never wakes the API.

In the Cloudflare dashboard → **R2 Object Storage** (it asks for a card the
first time; the free allowance is 10 GB):

1. **Create bucket**
   - Name: **`travel-planner-pages`**
   - Location: **Provide a location hint → Western Europe**
   - Storage class: Standard

   **Why Western Europe, not Eastern Europe (even from Tallinn).** Your
   browser never talks to R2 directly. The readers are the API in Frankfurt
   and the Pages Function, so what matters is being near Frankfurt. The
   Western Europe hint puts the data in that same area. Eastern Europe is a
   smaller region further away, which would slow every publish for no gain.

2. Leave the bucket **private**: no public access and no custom domain. It also
   holds pages waiting for the owner's approval, which must never be public.

3. **Manage API tokens → Create API token**
   - Name `travel-planner-api`
   - Permission **Object Read & Write**
   - **Apply to specific buckets only** → `travel-planner-pages`
   - TTL Forever

4. The next screen shows the keys **once**. Put them in `planner-api/.env.r2`:

   ```
   R2_ACCOUNT_ID='<32 hex characters>'
   R2_ACCESS_KEY_ID='<32 hex characters>'
   R2_SECRET_ACCESS_KEY='<64 hex characters>'
   ```

#### Create the file, private from the start
```bash
cd planner-api && touch .env.r2 && chmod 600 .env.r2 && open -e .env.r2
```

The **Account ID** is not the bucket name. It's your Cloudflare account's
32-character ID, shown on the R2 overview page and in the token's endpoint
URL, `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`.

> **Watch for pasted spaces.** Copying from the dashboard into TextEdit added
> about 150 invisible trailing spaces after two of the values, and R2 would
> have rejected them. If in doubt, check each value is exactly 32, 32 and 64
> characters long.

---

## Part 5: Secrets (Google Secret Manager)

The API needs four secrets. Cloud Run reads them from Secret Manager at start,
so they never sit in the service's settings, in git or in the image.

| Secret | What it is | Where it comes from |
|---|---|---|
| `jwt-secret` | Signs login sessions | Generated at random |
| `proxy-secret` | The header only the Cloudflare proxy knows | Generated at random |
| `db-password` | Neon password | `.env.neon` |
| `r2-secret-access-key` | R2 key | `.env.r2` |

`jwt-secret` matters most. Without it the app generates one inside its data
folder, which Cloud Run throws away on every restart, logging everybody out.

#### Turn on Secret Manager
```bash
gcloud services enable secretmanager.googleapis.com --project=travellingllama
```
#### Create the login-signing secret
```bash
openssl rand -base64 48 | tr -d '\n' | gcloud secrets create jwt-secret --data-file=- --replication-policy=automatic --project=travellingllama
```
#### Create the proxy secret
```bash
openssl rand -base64 32 | tr -d '\n' | gcloud secrets create proxy-secret --data-file=- --replication-policy=automatic --project=travellingllama
```
#### Store the Neon password, read from .env.neon
```bash
cd planner-api && (set -a; . ./.env.neon; set +a; printf '%s' "$DB_PASSWORD" | gcloud secrets create db-password --data-file=- --replication-policy=automatic --project=travellingllama)
```
#### Store the R2 key, read from .env.r2
```bash
cd planner-api && (set -a; . ./.env.r2; set +a; printf '%s' "$R2_SECRET_ACCESS_KEY" | gcloud secrets create r2-secret-access-key --data-file=- --replication-policy=automatic --project=travellingllama)
```

Each value is **piped** (`|`) straight into `gcloud`, so it's never printed.
To check a secret without revealing it, count its characters:

#### Show a secret's length only
```bash
gcloud secrets versions access latest --secret=db-password --project=travellingllama | wc -c
```

---

## Part 6: Where the API image lives (Artifact Registry)

Cloud Run runs a container image, and Artifact Registry stores it. **No Docker
Hub account is needed**: Docker signs in to Artifact Registry with your
`gcloud` login.

#### Turn on Artifact Registry and Cloud Run
```bash
gcloud services enable artifactregistry.googleapis.com run.googleapis.com --project=travellingllama
```
#### Create the image repository in Frankfurt
```bash
gcloud artifacts repositories create planner --repository-format=docker --location=europe-west3 --project=travellingllama
```

**The cleanup policy.** Storage is free only up to 0.5 GB, and each image is
about 160 MB, so old images must not pile up. Save this as
`cleanup-policy.json`:

```json
[
  {"name": "keep-latest-two", "action": {"type": "Keep"}, "mostRecentVersions": {"keepCount": 2}},
  {"name": "delete-everything-else", "action": {"type": "Delete"}, "condition": {"tagState": "any"}}
]
```

#### Apply the cleanup policy
```bash
gcloud artifacts repositories set-cleanup-policies planner --location=europe-west3 --policy=cleanup-policy.json --no-dry-run --project=travellingllama
```

Without `--no-dry-run`, the policy only reports what it would delete.

---

## Part 7: Deploy the API (Cloud Run)

### 7.1 Run the tests first

The image build skips the tests, because they need Docker. Run them first, and
check that the Postgres and R2 container tests actually **ran**. If Docker
can't be reached they are skipped, and the build still says BUILD SUCCESSFUL.

#### Run all tests
```bash
cd planner-api && ./gradlew test
```

The first deploy ran after 672 tests passed, with 0 skipped.

### 7.2 The settings file: `planner-api/.env.deploy`

Every setting for the service comes from this file, never from a hand-typed
command:

```
PROJECT_ID='travellingllama'
REGION='europe-west3'
SERVICE='planner-api'
IMAGE_TAG='v1'                      # bump for every new build
DOMAIN='travellingllama.fun'        # PUBLIC_BASE_URL and CORS_ORIGINS come from this
FEATURE_ENABLE_DATABASE='true'      # Postgres, not YAML files
PUBLISH_STORE='r2'                  # published pages go to R2
R2_BUCKET='travel-planner-pages'
MAIL_MODE='log'                     # invitation emails go to Cloud Logging
```

**Why a file.** Each deploy *replaces* the service's whole environment with
exactly what it's given. Typed by hand every time, a setting would sooner or
later be forgotten and silently dropped. With the file, the settings are
written down once, and the file is the record of what's deployed. A setting
changed only in the Cloud Run console is undone by the next deploy, so change
it here instead.

### 7.3 `./deploy.sh`

`planner-api/deploy.sh` reads `.env.deploy`, plus the Neon host and user from
`.env.neon` and the R2 IDs from `.env.r2`, and refuses to start if anything is
missing. Then it:

1. builds the image and pushes it to Artifact Registry;
2. lets Cloud Run's service account read the secrets (safe to repeat);
3. deploys, passing the settings as a temporary file and the four secrets by
   name from Secret Manager;
4. checks that health answers and that direct access without the proxy secret
   is refused.

#### Build, push and deploy
```bash
cd planner-api && ./deploy.sh
```
#### Redeploy the same image with changed settings
```bash
cd planner-api && ./deploy.sh --no-build
```

The first run takes 5–10 minutes, mostly the build, which includes a training
run that makes cold starts faster. It ends with:

```
health: {"status":"UP"}
anything else without the proxy secret (expect 403): 403
```

The 403 is correct: from now on the API only answers requests that came
through Cloudflare.

What the deploy settings mean:

| Setting | Why |
|---|---|
| `--allow-unauthenticated` | Cloudflare reaches the service over the internet. The proxy secret is what actually keeps everyone else out. |
| 0–1 instances | Scales to **zero** when idle, which keeps it free. The price is a **cold start of about 7–8 seconds** for the first visitor after a quiet spell. |
| 1 instance at most | Plenty for a group of friends, and the app is designed for one instance |
| 1 CPU, 1 GiB, CPU boost | Enough to start the Java app quickly |
| `MAIL_MODE=log` | New members' invitation emails, including their temporary passwords, appear in **Cloud Run → Logs** instead of being sent. Switching to real email later means `smtp` plus `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `MAIL_FROM` and an `SMTP_PASSWORD` secret. Never use `file` on Cloud Run: it writes to a disk that's thrown away. |
| No `BOOTSTRAP_OWNER_*` | On purpose. The first account is created in Part 9. |

The service's address is `https://planner-api-<project number>.europe-west3.run.app`.
Part 8 needs it:

#### Print the service address
```bash
gcloud run services describe planner-api --region=europe-west3 --project=travellingllama --format='value(status.url)'
```

The first start's logs showed it connected to Neon (PostgreSQL 17.11), with
Flyway at schema version 1, and exchange rates fetched.

---

## Part 8: The website (Cloudflare Pages)

Pages hosts the static website, meaning the HTML, CSS and JavaScript in
`planner-web/`, and runs two **Functions**: small pieces of code that run on
Cloudflare's servers.

| File | Catches | Does |
|---|---|---|
| `functions/api/[[path]].js` | every `/api/...` request | Forwards it to Cloud Run with the proxy secret |
| `functions/p/[[path]].js` | every `/p/...` request | Serves the published page from R2 |

`[[path]]` in a file name means "everything under this folder".

### 8.1 Tell the proxy where the API is

In `planner-web/wrangler.toml`, Cloudflare's settings file for the site, set the
production API address to the one from Part 7:

```toml
[env.production.vars]
API_ORIGIN = "https://planner-api-135344516404.europe-west3.run.app"
```

Cloudflare treats this file as the source of truth for the project's settings.
That includes the R2 connection (`PAGES_BUCKET` → `travel-planner-pages`), and
the dashboard shows them read-only.

### 8.2 Sign `wrangler` in

`wrangler` is Cloudflare's command-line tool, the counterpart of `gcloud`.

#### Sign in to Cloudflare (opens a browser; click Allow)
```bash
npx wrangler@4 login
```
#### Check who you are signed in as
```bash
npx wrangler@4 whoami
```

The login is saved on the Mac, so later commands don't ask again.

### 8.3 Create the Pages project

#### Create the project
```bash
npx wrangler@4 pages project create travel-planner --production-branch=main
```

`--production-branch=main` means a deploy labelled `main` is the live site. A
deploy with any other label becomes a preview at its own address.

**About `travel-planner-5n8.pages.dev`.** Every Pages project gets a free
`*.pages.dev` address, and those names are shared by everyone on Cloudflare.
`travel-planner.pages.dev` belonged to somebody else, so Cloudflare added the
random `-5n8`. There is still just **one** project, called `travel-planner`,
and the address can't be changed or removed without recreating the project.
It doesn't matter in practice: the app is meant to be used on
`travellingllama.fun`, and its links and cookies assume that domain.

### 8.4 Give Pages the proxy secret

Copied straight from Google to Cloudflare, so it never appears on screen:

#### Pipe the proxy secret from Secret Manager into Pages
```bash
cd planner-web && gcloud secrets versions access latest --secret=proxy-secret --project=travellingllama | npx wrangler@4 pages secret put PROXY_SECRET --project-name=travel-planner
```

It's stored encrypted, for the **production** environment only, so preview
deploys can't reach the real API. Afterwards the dashboard lists `PROXY_SECRET`
as a secret, and its value can't be read back.

### 8.5 Build and upload

#### Build the site
```bash
cd planner-web && npm ci && npm run build
```

`npm ci` installs the exact tool versions in `package-lock.json`, mainly Sass.
`npm run build` compiles the stylesheets and copies only what the site needs
into a fresh `dist/` folder: 37 files, about 750 KB. No `.env` file, source
stylesheet or `node_modules` ends up in it. `_headers` makes browsers check
for a new version of every file each time, so a deploy shows up at once.

#### Upload the site and its Functions
```bash
cd planner-web && npx wrangler@4 pages deploy --branch=main
```

Wrangler uploads `dist/`, builds the Functions from `functions/` (they are not
in `dist/`), and attaches the R2 bucket and `API_ORIGIN`. Each deploy also
gets its own permanent preview address, for example `https://1d6bae17.travel-planner-5n8.pages.dev`.
A warning about uncommitted git changes is harmless.

### 8.6 Attach the domain

Dashboard → **Workers & Pages → travel-planner → Custom domains → Set up a
custom domain** → `travellingllama.fun` → **Activate domain**. Because the
domain's DNS is already on Cloudflare (Part 1.1), Cloudflare adds the DNS
record and issues the HTTPS certificate itself. The status goes from
*Initializing* to *Active* within a few minutes.

### 8.7 Check the whole chain

| Request | Expect | Proves |
|---|---|---|
| `https://travellingllama.fun/login` | `200` | The website is served |
| `/no-such-page` | `404` | Unknown addresses really are 404, not the home page |
| `/api/v1/auth/me` | `401` "Please sign in." | Cloudflare → proxy → Cloud Run works, and the API accepted the proxy secret. A `403` would mean a wrong secret. |
| `/p/no-such-trip` | `404` | The published-pages Function is reading R2 |

#### Check one address's status code
```bash
curl -s -o /dev/null -w '%{http_code}  %{time_total}s\n' https://travellingllama.fun/api/v1/auth/me
```

The first API call after a quiet spell takes about 8 seconds (the cold start),
and the next one about 0.1 seconds.

---

## Part 9: The first account

**There is no sign-up page, on purpose.** Accounts only come into being when
somebody adds you to a trip. On a brand-new database nobody can sign in, so the
first account has to be put there. There are two ways.

### Option A: add just yourself (what was done)

**1. Make a password hash, in your own terminal:**

#### Generate a BCrypt hash of your password (it prompts; nothing is echoed)
```bash
htpasswd -nBC 10 me
```

It prints `me:$2y$10$...`. Keep everything **after** `me:`. The app stores
passwords as BCrypt hashes, never the password itself, and Spring accepts the
`$2y$` form that `htpasswd` makes.

**2. In the Neon console → SQL Editor, with the database set to `planner`:**

```sql
INSERT INTO users (id, email, screen_name, password_hash, must_change_password, created_at)
VALUES (
  gen_random_uuid()::text,
  lower(trim('YOUR_EMAIL')),
  'YOUR_SCREEN_NAME',
  'PASTE_HASH_HERE',
  false,
  now()
)
RETURNING id, email, screen_name, created_at;
```

**3. Sign in** at `https://travellingllama.fun`, create a trip, and add your
friends by email. Each one gets an account, and since `MAIL_MODE` is `log`,
their temporary passwords are in **Cloud Run → planner-api → Logs**.

> **This blocks Option B.** The importer only ever runs on an *empty*
> database. To bring the local data over later, empty the live database first,
> which **deletes everything created on the live site**:
>
> ```sql
> DELETE FROM trips;   -- takes every trip's destinations, checklist, itinerary and budget with it
> DELETE FROM users;
> ```

### Option B: bring over the local data (the importer)

The importer copies everything in the local YAML files (`planner-api/data/`)
into Neon: every account with its password hash, every trip and every record.
Everyone then signs in with the password they already had. It runs once, from
the Mac.

What it guarantees:

- **All or nothing.** One transaction; any failure leaves Neon as empty as it
  was.
- **It proves the copy before keeping it.** Every record is compared field by
  field, and every member's budget, including shares and settlements, is
  recalculated from both copies. Any difference, and nothing is kept.
- **It never merges.** A database with any user or trip in it is refused.
- **It only reads the local data**, and writes nothing into `data/`.

It was rehearsed against the real Neon database on 19 September with a dry run:
8 accounts, 1 trip, 9 destinations, 23 checklist items, 34 itinerary entries
and 17 expenses. Every check passed (92 records, 7 budget summaries), and the
run then rolled back.

#### 1. Dry run: import, verify, then undo
```bash
cd planner-api && set -a && . ./.env.neon && set +a && FEATURE_ENABLE_DATABASE=true DATA_DIR=/tmp/planner-import ./gradlew bootRun --args='--app.import.yaml-dir=./data --app.import.dry-run=true --server.port=0'
```

Look for **PASSED** on both verification lines and **Result: DRY RUN**.

#### 2. The real import
```bash
cd planner-api && set -a && . ./.env.neon && set +a && FEATURE_ENABLE_DATABASE=true DATA_DIR=/tmp/planner-import ./gradlew bootRun --args='--app.import.yaml-dir=./data --server.port=0'
```

It should end with **Result: COMMITTED**. Run again by mistake, it answers
**REFUSED** and changes nothing.

| Part of the command | What it does |
|---|---|
| `set -a && . ./.env.neon && set +a` | Loads the Neon settings for this one command, without printing them |
| `DATA_DIR=/tmp/planner-import` | A scratch folder, so nothing is written into the real `data/` |
| `--server.port=0` | A random free port, so it doesn't clash with a local API on 8080 |

Deliberately **not** imported: weather readings (a cache that refills itself),
exchange rates (refetched), and published pages (files, not records). After
importing, sign in and **publish each trip again** from its Publish tab. The
local accounts' emails are the example ones (`you@example.com` and so on), so
change them to real addresses once signed in.

---

## Part 10: Keeping it free

| Service | Free allowance | If it runs out |
|---|---|---|
| Cloud Run | 180,000 vCPU-s, 360,000 GiB-s, 2M requests / month | **Bills** (the €1 alert emails) |
| Artifact Registry | 0.5 GB | **Bills**, kept in check by the cleanup policy |
| Neon | 100 CU-hours, 0.5 GB storage, 5 GB transfer / month | Database pauses until the 1st, **no charge** |
| Pages Functions | 100,000 requests / **day** | Requests fail until tomorrow, **no charge** |
| R2 | 10 GB, 1M writes, 10M reads / month | **Bills** |

What keeps it inside those limits: the API scales to zero and runs at most one
instance, and its database pool keeps no idle connections, so Neon can sleep.
The cleanup policy caps stored images, and static files on Pages are free
without limit.

To check where it stands:

- **In Claude Code**, ask about free-tier usage. The global
  `free-tier-usage` skill prints one table for all five services.
- **In a terminal**, run `~/.claude/skills/free-tier-usage/usage-report.sh`.
- **By hand**, follow [free-tier-usage.md](free-tier-usage.md), which walks
  through each dashboard.

On the first day, everything was below 1 % except Artifact Registry, which
holds one image and so reads about 33 %.

---

## Everyday tasks

#### Ship a new version of the API (after bumping IMAGE_TAG in .env.deploy)
```bash
cd planner-api && ./deploy.sh
```
#### Change an API setting (edit .env.deploy first)
```bash
cd planner-api && ./deploy.sh --no-build
```
#### Ship a new version of the website
```bash
cd planner-web && npm run build && npx wrangler@4 pages deploy --branch=main
```
#### Read the API's recent logs (invitation emails show up here)
```bash
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="planner-api"' --project=travellingllama --limit=50 --freshness=1h --format='value(textPayload)'
```
#### Check the API from outside
```bash
curl -s https://travellingllama.fun/api/v1/auth/me
```

---

## Things that tripped us up

| What happened | Cause | Fix |
|---|---|---|
| Local API wouldn't start: "port already in use" | An old API process from the day before still held port 8080 | README → *Stop everything* before starting |
| Neon project on Postgres 18 | Neon's default | Recreated on 17 |
| Neon connection string ends in `&channel_binding=require` | A `psql`-only option | Removed it from `DB_URL` |
| R2 values 180+ characters long | Trailing spaces pasted from the dashboard | Trimmed; each value is 32, 32 or 64 characters |
| `travel-planner-5n8.pages.dev` | The `pages.dev` name was already taken | Nothing to fix. The domain is the real address. |
| Budget didn't show from `gcloud` | The Billing Budget API was off, and takes a minute to turn on | `gcloud services enable billingbudgets.googleapis.com` |
| First visit to the site slow (~8 s) | Cloud Run starting from zero | Expected. That's the price of free. |
| Signing in impossible on a fresh database | No sign-up page, by design | Part 9 |

---

## Local development

Unchanged by all of the above. `./gradlew bootRun` on :8080 plus `./serve.sh`
on :3000 works exactly as before, in YAML mode, and the README has the start and
stop commands. The local YAML files are also the fallback copy of the data.

To run through the same Cloudflare proxy locally, write `planner-web/.dev.vars`
(it's gitignored; see `.dev.vars.example`) and then:

#### Build the site and serve it through the local proxy
```bash
cd planner-web && npm run pages:dev
```

That serves on :8788 and forwards `/api/*` to `API_ORIGIN` from
`wrangler.toml`, which is `http://localhost:8080` by default.

#### Start local Postgres
```bash
docker compose up -d postgres
```

**Container tests under Colima.** Testcontainers can't find Colima's Docker on
its own, and when it can't find Docker, it **skips** the Postgres and MinIO
tests rather than failing them: a green run that tested nothing. On this Mac
that's fixed once, in two places, because Testcontainers reads the two settings
from different places:

| Setting | Where | Why |
|---|---|---|
| `docker.host=unix:///Users/joeydevivre/.colima/default/docker.sock` | `~/.testcontainers.properties` | Where the Docker API is, from the Mac's side |
| `export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` | `~/.zshrc` | The socket's path *inside* the Colima VM, mounted into Testcontainers' cleanup container. Only read from the environment. |

With both set, a plain `./gradlew test` in a new terminal runs every container
test. An IDE started from the Dock doesn't read `~/.zshrc`: run the tests
through Gradle from a terminal, or add the variable to the IDE's test
configuration.
