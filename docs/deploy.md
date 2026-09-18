# Deploying: Cloud Run + Neon + Cloudflare

The free-tier deployment described in
`.claude/plans/2026-09-18-postgres-and-cloud-run.md`. Nothing here has been run
yet — this is the runbook for when you do.

> **Not deployable yet.** Database mode can't start until the Postgres
> repositories exist (Phase 2), and your YAML data needs the importer (Phase 3).
> Everything else below is ready.

```
yourdomain.com  (Cloudflare DNS + TLS)
 ├─ /*       Cloudflare Pages → planner-web/dist
 ├─ /api/*   Pages Function → Cloud Run (API), with X-Proxy-Secret
 └─ /p/*     Pages Function → R2 bucket (published pages)

Cloud Run  europe-west3 · 0–1 instances   ──►   Neon Postgres  aws-eu-central-1
```

Cloud Run and Neon must both be in **Frankfurt**: a trip loads with about eight
queries, so the distance between them is paid eight times per screen.

---

## What you'll need

- A Google Cloud project **with billing enabled** — Cloud Run requires a billing
  account even when everything stays inside the free tier.
- A Neon account.
- A Cloudflare account with **your domain's DNS on Cloudflare**.
- `gcloud` and Docker on your machine. Your Mac is x86-64, so the image you
  build locally is already the `linux/amd64` image Cloud Run runs.

Replace `PROJECT_ID`, `yourdomain.com` and the other placeholders as you go.

---

## 1. Neon

In the Neon console, create a project:

- **Region:** AWS Europe Central 1 (Frankfurt)
- **Postgres version:** 17 — the version the local `compose.yaml` and the tests use

From *Connection details*, copy the **direct** connection string — **not** the
one whose host contains `-pooler`. Flyway takes a session-level lock that
Neon's pooler cannot hold.

Turn it into three settings for Cloud Run:

| Setting | Value |
|---|---|
| `DB_URL` | `jdbc:postgresql://<host>/<database>?sslmode=require` |
| `DB_USER` | the role name |
| `DB_PASSWORD` | the role's password → goes in Secret Manager (step 3) |

Flyway creates the tables on the API's first start. Leave the database empty.

---

## 2. Cloudflare R2

In the Cloudflare dashboard → **R2**:

1. Create a bucket named **`travel-planner-pages`**, location hint
   **Western Europe**. Leave it **private** — no public access and no custom
   domain on the bucket. The Pages Function is the only reader, and it only
   serves `published/…`; the bucket also holds pending pages that must never be
   public.
2. **Manage R2 API tokens** → create a token with **Object Read & Write**,
   scoped to that one bucket.
3. Note the **Account ID**, **Access Key ID** and **Secret Access Key**. The
   secret is only shown once.

---

## 3. Secrets (Google Secret Manager)

Four secrets. The API reads them as environment variables.

#### Enable Secret Manager
```bash
gcloud services enable secretmanager.googleapis.com --project=PROJECT_ID
```
#### Create the JWT signing secret
```bash
openssl rand -base64 48 | tr -d '\n' | gcloud secrets create jwt-secret --data-file=- --project=PROJECT_ID
```
#### Create the proxy secret
```bash
openssl rand -base64 32 | tr -d '\n' | gcloud secrets create proxy-secret --data-file=- --project=PROJECT_ID
```
#### Store the Neon password
```bash
printf '%s' 'NEON_PASSWORD' | gcloud secrets create db-password --data-file=- --project=PROJECT_ID
```
#### Store the R2 secret access key
```bash
printf '%s' 'R2_SECRET_ACCESS_KEY' | gcloud secrets create r2-secret-access-key --data-file=- --project=PROJECT_ID
```
#### Print the proxy secret, for Cloudflare in step 6
```bash
gcloud secrets versions access latest --secret=proxy-secret --project=PROJECT_ID
```

`JWT_SECRET` matters most: without it the app generates one into its data
directory, which on Cloud Run is discarded on every restart — logging everybody
out each time.

---

## 4. Artifact Registry

#### Enable Artifact Registry and Cloud Run
```bash
gcloud services enable artifactregistry.googleapis.com run.googleapis.com --project=PROJECT_ID
```
#### Create the image repository
```bash
gcloud artifacts repositories create planner --repository-format=docker --location=europe-west3 --project=PROJECT_ID
```

**Add a cleanup policy.** Artifact Registry is free only up to 0.5 GB, and each
image is about 160 MB. Without a cleanup policy, old images pile up and quietly
start costing money. Save this as `cleanup-policy.json`:

```json
[
  {
    "name": "keep-latest-two",
    "action": { "type": "Keep" },
    "mostRecentVersions": { "keepCount": 2 }
  },
  {
    "name": "delete-everything-else",
    "action": { "type": "Delete" },
    "condition": { "tagState": "any" }
  }
]
```

#### Apply the cleanup policy
```bash
gcloud artifacts repositories set-cleanup-policies planner --location=europe-west3 --policy=cleanup-policy.json --no-dry-run --project=PROJECT_ID
```

Without `--no-dry-run` the policy only reports what it would delete.

---

## 5. Build, push and deploy the API

Run the tests first. The image build skips them, because they need Docker.
Check the report shows the Postgres and MinIO tests as *run*, not skipped —
see *Local development* below.

#### Run the tests
```bash
cd planner-api && ./gradlew test
```
#### Let Docker push to Artifact Registry
```bash
gcloud auth configure-docker europe-west3-docker.pkg.dev
```
#### Build the image
```bash
docker build -t europe-west3-docker.pkg.dev/PROJECT_ID/planner/planner-api:v1 planner-api
```
#### Push the image
```bash
docker push europe-west3-docker.pkg.dev/PROJECT_ID/planner/planner-api:v1
```

The service runs as the project's default compute service account, which
needs to read the secrets:

#### Grant the service account access to the secrets
```bash
gcloud projects add-iam-policy-binding PROJECT_ID --member="serviceAccount:$(gcloud projects describe PROJECT_ID --format='value(projectNumber)')-compute@developer.gserviceaccount.com" --role=roles/secretmanager.secretAccessor
```

#### Deploy to Cloud Run
```bash
gcloud run deploy planner-api --project=PROJECT_ID --region=europe-west3 --image=europe-west3-docker.pkg.dev/PROJECT_ID/planner/planner-api:v1 --allow-unauthenticated --min-instances=0 --max-instances=1 --cpu=1 --memory=1Gi --cpu-boost --set-env-vars=FEATURE_ENABLE_DATABASE=true,DB_URL='jdbc:postgresql://NEON_HOST/NEON_DB?sslmode=require',DB_USER=NEON_ROLE,COOKIE_SECURE=true,PUBLIC_BASE_URL=https://yourdomain.com/p,CORS_ORIGINS=https://yourdomain.com,PUBLISH_STORE=r2,R2_ACCOUNT_ID=R2_ACCOUNT_ID,R2_BUCKET=travel-planner-pages,R2_ACCESS_KEY_ID=R2_ACCESS_KEY_ID,MAIL_MODE=log --set-secrets=JWT_SECRET=jwt-secret:latest,DB_PASSWORD=db-password:latest,R2_SECRET_ACCESS_KEY=r2-secret-access-key:latest,PROXY_SECRET=proxy-secret:latest
```

What those settings do:

| Setting | Why |
|---|---|
| `--allow-unauthenticated` | Cloudflare calls the service over the public internet. `PROXY_SECRET` is what actually keeps everyone else out: any request without it gets a 403. |
| `--min-instances=0` | Scales to zero when idle, which keeps it in the free tier. The trade-off is a cold start of roughly 7 s on the first request after a quiet period — measured with class-data sharing, versus about 9 s without. |
| `--max-instances=1` | One instance is plenty, and avoids problems from running more than one. |
| `--cpu-boost` | Extra CPU while the JVM starts. |
| request-based billing | The default (CPU only while a request runs). Don't switch to always-on billing: it leaves the free tier. |
| `MAIL_MODE=log` | Start here: invitation emails, including new members' passwords, go to Cloud Logging. Later, switch to `smtp` and set `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `MAIL_FROM` and an `SMTP_PASSWORD` secret. **Never use `file` mode on Cloud Run** — it writes to a disk that is discarded. |
| `BOOTSTRAP_OWNER_*` | Not set on purpose: the importer brings your account over. |

#### Print the service URL, for step 6
```bash
gcloud run services describe planner-api --region=europe-west3 --project=PROJECT_ID --format='value(status.url)'
```

#### Check it answers
```bash
curl -s https://SERVICE_URL/actuator/health
```

A direct call to anything else should now return `403 proxy_required` — that's
correct.

---

## 6. Cloudflare Pages

First, put the Cloud Run URL from step 5 into `planner-web/wrangler.toml`,
replacing the placeholder under `[env.production.vars]`:

```toml
[env.production.vars]
API_ORIGIN = "https://planner-api-XXXXXXXX.europe-west3.run.app"
```

Once `wrangler.toml` exists, Cloudflare treats it as the source of truth for the
project's variables and bindings, including the R2 binding `PAGES_BUCKET` →
`travel-planner-pages`. The dashboard then shows them read-only.

#### Log in to Cloudflare
```bash
npx wrangler@4 login
```
#### Create the Pages project
```bash
npx wrangler@4 pages project create travel-planner --production-branch=main
```
#### Store the proxy secret in Pages (paste the value printed in step 3)
```bash
cd planner-web && npx wrangler@4 pages secret put PROXY_SECRET --project-name=travel-planner
```
#### Build the site
```bash
cd planner-web && npm ci && npm run build
```
#### Deploy the site and its Functions
```bash
cd planner-web && npx wrangler@4 pages deploy --branch=main
```

Then, in the dashboard → **Workers & Pages → travel-planner → Custom domains**,
add `yourdomain.com`. With the domain's DNS already on Cloudflare, it sets up
the record and certificate for you.

The Functions' edge cache (for `/p/*`) only works on your custom domain, not on
the `*.pages.dev` address. That's expected.

---

## 7. Your data, and first sign-in

*Pending Phase 3.* The importer copies your YAML data (accounts included, so
existing passwords keep working) into Neon from your laptop, then you publish
once to render the pages into R2. Its exact commands will be added here.

---

## 8. Free-tier guardrails

Set a **$1 budget alert** on the billing account, so any unexpected charge
emails you at once rather than at the end of the month:

#### Find the billing account ID
```bash
gcloud billing accounts list
```
#### Create the budget alert
```bash
gcloud billing budgets create --billing-account=BILLING_ACCOUNT_ID --display-name="travel-planner" --budget-amount=1USD --threshold-rule=percent=0.5 --threshold-rule=percent=1.0
```

| Free tier | What keeps you inside it |
|---|---|
| Cloud Run — 180,000 vCPU-s, 360,000 GiB-s, 2M requests a month | Scale to zero, one instance, request-based billing |
| Neon — 0.5 GB, 100 CU-hours a month, sleeps after 5 min idle | The connection pool keeps no idle connections, so Neon can sleep |
| Artifact Registry — 0.5 GB | The cleanup policy in step 4 |
| Cloudflare Pages Functions — 100k requests a day | Only `/api/*` and `/p/*` count; static files are free and unmetered |
| Cloudflare R2 — 10 GB, no egress fees | A published trip is about 300 KB |

---

## Local development

Unchanged. `./gradlew bootRun` on :8080 plus `./serve.sh` on :3000 works
exactly as before, still in YAML mode.

To run through the same Cloudflare proxy locally, write `planner-web/.dev.vars`
(it's gitignored — see `.dev.vars.example`) and then:

#### Build the site and serve it through the local proxy
```bash
cd planner-web && npm run pages:dev
```

That serves on :8788 and forwards `/api/*` to `API_ORIGIN` from
`wrangler.toml` — `http://localhost:8080` by default.

#### Start local Postgres
```bash
docker compose up -d postgres
```

**Container tests under Colima.** Testcontainers can't find Colima's Docker on
its own, and when it can't find Docker it **skips** the Postgres and MinIO tests
rather than failing them — a green run that tested nothing. On this Mac that's
fixed once, in two places, because Testcontainers reads the two settings from
different places:

| Setting | Where | Why |
|---|---|---|
| `docker.host=unix:///Users/joeydevivre/.colima/default/docker.sock` | `~/.testcontainers.properties` | Where the Docker API is, from the Mac's side |
| `export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` | `~/.zshrc` | The socket's path *inside* the Colima VM, mounted into Testcontainers' cleanup container. Only read from the environment. |

With both set, a plain `./gradlew test` in a new terminal runs every container
test. An IDE started from the Dock doesn't read `~/.zshrc`: run the tests
through Gradle from a terminal, or add the variable to the IDE's test
configuration.
