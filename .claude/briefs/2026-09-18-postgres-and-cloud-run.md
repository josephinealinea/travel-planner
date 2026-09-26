# PostgreSQL behind the flag, and a free-tier deployment

**Status:** **done** — Phases 1–2 committed (`b2223d9`); Phase 3 verified 2026-09-19, uncommitted. Next: deploy (`../../docs/deploy/deploy.md`), by 4 Oct.
**Date:** 2026-09-18
**Plan:** `.claude/plans/2026-09-18-postgres-and-cloud-run.md`

## Job
1. Make `feature-enable-database` real: every record in PostgreSQL when it's on,
   exactly today's YAML behaviour when it's off.
2. Flyway owns the schema — **schema only, never data**.
3. A Docker image Cloud Run can run, and everything else the deployment needs.
4. Get the user's real trips into the database before the trip.

## Why
The app ran only on the user's laptop, so their friends in Singapore couldn't
reach it, and the trip to South America starts **24 Oct**. Cloud Run's disk is
temporary, so records had to move to a database — and the user wants to stay
on free tiers: Cloud Run, Neon, Cloudflare.

## Decisions (agreed 2026-09-18)
- **Spring JDBC (`JdbcClient`), not JPA.** The domain classes are the YAML
  documents; JPA annotations on them would sit on objects YAML mode serialises —
  the class of accident CLAUDE.md's Traps keeps recording.
- **Same origin, via Cloudflare — not the same container.** Auth cookies are
  host-only and `SameSite=Lax`, and the CSRF token is read by JavaScript, so the
  browser must see one origin. Cloudflare Pages serves the frontend and proxies
  `/api/*` to Cloud Run.
- **Published pages in R2, behind a `PageStore`.** A mounted Cloud Storage
  bucket was the first plan; it was dropped when Cloud Storage FUSE turned out
  to refuse directory renames, which approving a publish request does. R2 also
  means a shared trip link never waits for the API to wake up.
- **Exchange rates refresh on read when stale**, because free-tier Cloud Run
  has no CPU between requests for a cron to use.
- **Region: Frankfurt for Cloud Run and Neon** — the user plans every trip from
  Tallinn, friends in Singapore read at ~160 ms, and on the road people read the
  published page from Cloudflare's edge.
- **Existing data moves through an importer** — application code run once,
  verifying itself — because a fresh database is empty and Flyway stays
  schema-only.
- **Testcontainers under Colima is configured in the user's home**:
  `~/.testcontainers.properties` for the Docker host, `~/.zshrc` for the socket
  path (it can only come from the environment).

## Guardrails
- **Flag off must behave exactly as before.** No service changed; YAML mode is
  pinned by a startup test written before the database dependency was added.
- **The two stores must be provably equivalent**: one contract per repository,
  run against both.
- **No field may go unstored**: every-field round-trip guards on every entity.
- **Order that decides money is stored as order** (members, sharers).
- **Nothing private in the image**: `data/` excluded; checked in the built image.
- **The user's real data is read-only**, and only ever copied.
- **No commits by Claude** — the user's hook forbids it; everything was merged as
  uncommitted changes.

## Done means
Phases 1–2 — verified 2026-09-18:

- [x] Flag off: all 276 original tests green, no service class changed,
      `YamlModeStartupTest` proves no DataSource or Flyway is configured
- [x] Flag on: `DatabaseModeApplicationTest` starts the whole app on Postgres,
      Flyway applies V1, the bootstrap owner is a row, and a trip → destination
      (3 seeded checklist items) → expense runs through the real services and
      its delete leaves every table empty
- [x] Every repository's contract passes against YAML and Postgres; every
      entity has an every-field round-trip guard
- [x] **673 tests, 0 failures, 0 skipped** — every container test ran
- [x] Image: `linux/amd64`, 158 MB, non-root, no `users.yml`/`secret.yml`/trips
      inside; cold start 6.7 s with class-data sharing vs 8.9 s without (1 CPU,
      1 GiB), archive confirmed mapping at runtime
- [x] Through the local Cloudflare proxy: login, `/auth/me`, a create with the
      CSRF token (201) and without it (403); a direct call without the proxy
      secret refused (403); unknown paths a real 404; `js/` served `no-cache`
- [x] `../../docs/deploy/deploy.md` covers every resource, setting and secret

Phase 3:

- [x] The importer, rehearsed on a **copy** of the user's real data (1 trip, 8
      accounts, 6 members, 9 destinations, 23 checklist, 34 itinerary, 17
      budget rows) into a throwaway Postgres 17: dry run PASSED both
      verifications (92 records, 7 budget summaries) and left 0 rows; the real
      run COMMITTED with identical counts; a second run was REFUSED
- [x] `YamlImporterTest`: 12 tests, including settlements worked out by hand
      (the odd cent to the first sharer) and a mid-import failure that leaves
      every table empty. Mutation-checked: removing the timestamp restore made 6
      of them fail
- [x] End to end: a Postgres-mode API on the imported data and a YAML-mode API
      on a second copy, both signed into with the user's **existing password**,
      returned the same trip — **zero differences** across members,
      destinations, checklist, itinerary, budget rows, shares and settlements,
      beyond the three set aside in advance (rate tables, converted totals,
      published pages)
- [x] The app in database mode, in the browser, on the imported copy — every
      tab's counts and the Settle Expenses table match
- [x] **672 tests, 0 failures, 0 skipped**; `../../docs/deploy/deploy.md` step 7 is the
  import procedure
- [x] CLAUDE.md: storage, schema rules, page store, deployment, rates
      refresh-on-read, three new Traps; the false "every repository is an
      interface" claim corrected
- [x] The two duplicate every-field helpers merged into `storage/EveryField`
      (test), and the duplicate `YamlDestinationRepositoryContractTest` removed

## Found along the way
- **Only 2 of 8 repositories were interfaces**, despite CLAUDE.md saying all
  were. The other six became interfaces under their existing names.
- **Container tests were silently skipped twice**: once for a Testcontainers
  version Docker 29 refuses, once for Colima's socket. Both fixed; both in Traps.
- **Agents C and E both ran out of usage mid-task.** C's unfinished checks
  (image, cold start, proxy end to end) and `../../docs/deploy/deploy.md` were done by the
  parent; so were E's tests, rehearsal and docs — its seven main classes were
  complete, reviewed, and kept unchanged.
- **A GitHub personal access token sits in plain text in `~/.zshrc`**, and was
  printed into a session transcript while checking the file before editing it.
  The user was told; rotating it is theirs to decide.
