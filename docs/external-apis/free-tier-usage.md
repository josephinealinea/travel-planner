# Checking free-tier usage by hand

The deployment ([deploy.md](../deploy/deploy.md)) is built to stay inside three free
tiers. This page shows where to look in each provider's dashboard, what each
figure means, and what happens if it is exceeded. To get the same figures in
one table from the terminal, run the `free-tier-usage` skill, or run
`~/.claude/skills/free-tier-usage/usage-report.sh` directly.

About two minutes once a month is enough. The €1 Google budget alert covers
the rest of the time.

## The limits at a glance

| Service | Free allowance | Resets | Over the limit |
|---|---|---|---|
| Cloud Run | 180,000 vCPU-seconds, 360,000 GiB-seconds, 2M requests | monthly | **bills** |
| Artifact Registry | 0.5 GB stored | monthly | **bills** |
| Neon | 100 CU-hours compute, 0.5 GB storage, 5 GB data transfer | monthly (1st) | database **pauses**, no charge |
| Cloudflare Pages Functions | 100,000 requests | **daily** | requests **fail**, no charge |
| Cloudflare R2 | 10 GB stored, 1M Class A (write) ops, 10M Class B (read) ops | monthly | **bills** |
| Cloudflare Pages static files | unlimited | — | — |

Only three of these can cost money: Cloud Run, Artifact Registry and R2. The
other two stop working instead of billing.

---

## Google Cloud

Sign in at [console.cloud.google.com](https://console.cloud.google.com) and
check that the project selector at the top shows **travellingllama**.

### Money actually charged

**Billing → Reports.** Set the time range to *Current month* and group by
*Service*. The total should read **€0.00**. This is the only place in this
guide that shows money rather than usage.

**Billing → Budgets & alerts** should list `travel-planner`: €1, alerts at 50 %,
90 % and 100 %. An alert only emails; it doesn't stop anything, so act on one
when it arrives.

### Cloud Run

**Cloud Run → planner-api → Metrics.** Pick a 30-day range.

| Chart | Healthy |
|---|---|
| Request count | Anything below ~65,000 a day stays inside 2M a month |
| Billable container instance time | Short spikes while someone uses the app, zero in between |
| Container instance count | Drops back to **0** after about 15 idle minutes |

Billable instance time is the one that matters. The service has 1 vCPU and
1 GiB, so every billable second uses one vCPU-second and one GiB-second of
the allowance. The first limit it would reach is 180,000 vCPU-seconds, which
is 50 hours of serving a month.

**Warning sign:** an instance count that never returns to 0. Something is
keeping it awake: a monitor pinging it, or a bot. The logs (**Logs** tab) show
what is calling it.

### Artifact Registry

**Artifact Registry → Repositories → planner** (region europe-west3). The
repository list shows its size. Each image is about 160 MB, and the cleanup
policy keeps the newest two, so ~330 MB is the normal ceiling. If it's above
500 MB, check under **Cleanup policies** that the policy is still there and
not set to dry run.

---

## Neon

Sign in at [console.neon.tech](https://console.neon.tech) and open project
**travel-planner**.

The **project dashboard** shows this month's usage:

| Figure | Limit | Notes |
|---|---|---|
| Compute | 100 CU-hours | Compute size × time awake. With the database asleep, it doesn't grow. |
| Storage | 0.5 GB | The whole trip data is tens of MB |
| Data transfer | 5 GB | Data sent from Neon to Cloud Run |

**Monitoring** shows when the compute was awake. Awake periods should line up
with times someone used the app, and it should suspend after about 5 minutes
of quiet. If it's awake around the clock, something is holding a connection
open.

**Branches → production → Computes** shows the autoscaling range. A lower
maximum burns compute hours more slowly. The app runs comfortably on 1 CU.

Neon on the free plan **never bills**. At 100 CU-hours the database is
suspended until the 1st of next month, and until then the site answers with
errors.

---

## Cloudflare

Sign in at [dash.cloudflare.com](https://dash.cloudflare.com).

### Pages Functions

**Workers & Pages → travel-planner → Metrics** (or **Functions → Metrics**,
depending on the dashboard version). The request chart counts Function
invocations, which are the `/api/*` proxy and `/p/*` published pages. Static
files (HTML, CSS, JS) aren't counted and are free without limit.

The limit is **100,000 a day**, reset at midnight UTC. Past it, Functions
requests fail with an error until the next day, and nothing is charged.

### R2

**R2 Object Storage → travel-planner-pages → Metrics** shows storage and
operation counts. The account-wide totals are on the R2 overview page.

| Class | Counts | Free per month |
|---|---|---|
| Class A (writes) | Publishing a page, listing, bucket changes | 1,000,000 |
| Class B (reads) | A reader opening a published page, bucket reads | 10,000,000 |
| Free | Deletes | unlimited |

A published trip is about 300 KB, so storage won't matter. Operations only add
up if a published page gets very popular, and even then, 10M reads is a lot of
readers. R2 has no egress fees.

**Manage account → Billing → Billable usage** shows what, if anything, would be
charged this month.

---

## If something is climbing

| Seen | Likely cause | What to do |
|---|---|---|
| Cloud Run instance never at 0 | Something polls the API | Find the caller in Cloud Run → Logs |
| Neon compute awake all day | A connection held open | Check Cloud Run is scaling to 0, since it holds the pool |
| Neon compute hours rising fast | Autoscaling maximum is high | Lower the maximum to 1 CU |
| Pages Functions near 100k/day | Heavy traffic or a bot on `/api` or `/p` | Cloudflare → Security → WAF rate limiting |
| Artifact Registry above 500 MB | Cleanup policy missing or dry run | Re-apply it: deploy.md, Part 6 |
