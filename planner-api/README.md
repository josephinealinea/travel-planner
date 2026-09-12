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
PORT=8090 PUBLIC_BASE_URL=http://localhost:8090/p ./gradlew bootRun
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
