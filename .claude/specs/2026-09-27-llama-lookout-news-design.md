# Llama Lookout: design spec

Status: DRAFT for review. No open questions. No code written. After
approval, the next document is an implementation plan in `.claude/plans/`.

## Goal

Below the Destinations list on the Destinations tab, a carousel — "🦙 Llama
Lookout" — shows a few recent, place-relevant news stories per destination
(headline, image, source, date, link out). It answers "what's happening
where I'm going right now", using two free news APIs, without ever storing an
article or spending more of either API's daily quota than necessary.

## Scope

In:
- A member-only endpoint, `GET /trips/{id}/news`, returning up to 3 articles
  per unique destination name in the trip.
- A `news/` module in the API: two clients (NewsData.io, Currents), a
  provider-selection/quota layer reusing the existing `usage/` module, no
  domain storage.
- A carousel component on the Destinations tab.
- `docs/external-apis/newsdata.md` and `currents.md`, plus their README rows.

Out (not v1):
- Any server-side persistence of articles (no YAML file, no table, no
  import-verifier entry — see Caching).
- Published-page news (member-only feature; a published page has no signed-in
  reader to personalise it for, and no API behind it to call on their behalf).
- Merging/deduping results across the two providers for the same destination —
  each destination's lookup uses exactly one provider per call.
- Any paid plan of either service.

## What the probes established

Live calls, 27 Sep 2026. These are facts, not assumptions.

- Both APIs return **metadata and a link, never full article body**. NewsData's
  free plan explicitly marks `content` as `"ONLY AVAILABLE IN PAID PLANS"`;
  Currents never includes a content field at all. Both give title,
  description, image URL, published date and the article URL — enough for a
  card, never enough to read the story on our page. The image is a bare URL on
  the source's own CDN; we only ever put it in an `<img src>`, never fetch or
  store it.
- **Filtering by `country=` is low-relevance**, on both APIs and whether
  batched or not. A single country (`country=pe`) and a 5-country batch
  (`country=pe,bo,ar,cl,ec`) both return mostly generic wire-service stories
  tagged with 20–30 countries at once ("storm hits US" tagged onto every
  country in the Americas) with only the occasional genuinely single-country
  item mixed in. Currents doesn't support multi-country batching at all —
  `country=PE,BO` is a hard `400 Bad request` ("Allowed values" lists single
  codes only).
- **Filtering by place name (`q=`/`keywords=`) is far more relevant** — `q=Cusco`
  returned real, current Cusco/Peru stories (a missing hiker, a statue
  installation) with no noise.
- **Bare place names are ambiguous.** `q=La Paz` alone pulled in La Paz,
  **Mexico** (Baja California Sur) results — a national park, an unrelated
  arrest — mixed with Bolivia's La Paz. Confirmed on both APIs.
- **Qualifying the query with the country name fixes it.** `q="La Paz" Bolivia`
  (NewsData) and `keywords="La Paz" Bolivia` (Currents) both snapped back to
  clean, Bolivia-only results.
- **OR'ing multiple place names into one call to batch destinations degrades
  relevance** — the first result or two stay on-topic, then it drifts into
  unrelated stories. Batching within `q=` is not a shortcut around per-place
  calls.
- Both APIs already take a `language=` parameter using the same two-letter
  codes this app uses elsewhere (email templates, `RequestLocale`).

## Query construction

For each **unique** `Destination.name` in a trip (dedup, same idea as
weather's dedup by rounded coordinates — a country's second city gets its own
call, a repeated city name does not), the query term is:

```
"<destination.name>" <CountryName>
```

`CountryName` is resolved from `Destination.countryCode` via the same
generated table the publisher already ships: `publish/countries.json`
(written by `npm run countries`, kept in sync with `countries.js` by `npm run
check`). The API loads that file once at startup rather than adding a third
copy of the code→name table. No destination is ever queried by country alone
— `Destination.name` is always present (it is how a destination is entered),
so there is no country-only case to special-case.

## Roles of the two services

| Service | Daily limit | Role |
|---|---|---|
| NewsData.io | 200 | Preferred for the first lookup in a batch; otherwise picked by coin flip alongside Currents (see Provider selection). |
| Currents | 250 | Same as above — the two are peers once the first pick is made. |

Neither is "primary" in the AeroDataBox/AviationStack sense (source of truth
vs. narrow fallback): both return the same shape of usable result, so the
split here is purely about spreading load across two independent free quotas,
not about one service being more capable.

## Provider selection

Per destination-lookup, in the order destinations are processed for one
request:

1. **The first lookup in the batch always tries NewsData first.**
2. **Every subsequent lookup flips a coin** between NewsData and Currents.
3. Before calling the chosen provider, check its daily cap (see Quota). If
   that provider is capped, use the other one instead, without consuming a
   coin flip's worth of randomness elsewhere in the batch.
4. If **both** providers are capped, that destination is skipped — no call,
   no error, it simply contributes no articles to the response.
5. The circuit breaker (`resilience/CircuitBreaker`) also gates each provider,
   the same three calls the flights clients use (`isOpen`/`success`/`failure`),
   for outages independent of the quota — a provider that's timing out is
   skipped the same way a capped one is, and recovers on its own schedule.

This is intentionally simpler than the flights fallback chain: there is no
"ask B only because A just came back empty" step, because relevance here
comes from the query text (place + country), not from which provider answered.

## Quota (daily cap, 90% default, hard stop)

Extends the existing `usage/` module rather than adding a new table:
`ApiUsageRepository.tryAcquire(service, period, cap)` already takes an
arbitrary period string (named `month` today only because that's its one
caller). `ApiUsageService` gains a sibling to `monthOf`:

```java
static String dayOf(Instant instant) {
    var utc = instant.atZone(ZoneOffset.UTC);
    return "%04d-%02d-%02d".formatted(utc.getYear(), utc.getMonthValue(), utc.getDayOfMonth());
}
```

and a `tryAcquireDaily(service, cap)` / `callsToday(service)` pair mirroring
the monthly ones. No schema or repository change — a day is just a different
string in the same `(service, period, calls)` row shape, on both the YAML and
JDBC implementations.

New `NewsProperties` (`app.news`, its own `@ConfigurationProperties` record,
per the "new settings get their own record" convention):

```yaml
app:
  news:
    enabled: true
    cap-fraction: 0.9              # NEWS_CAP_FRACTION
    max-articles-per-destination: 3 # NEWS_MAX_ARTICLES_PER_DESTINATION, e.g. 5
    newsdata:
      key: ${NEWSDATA_KEY:}
      daily-limit: 200
    currents:
      key: ${NEWSCURRENTS_KEY:}
      daily-limit: 250
```

Effective cap per provider = `floor(daily-limit * cap-fraction)`, so
`tryAcquireDaily("newsdata", 180)` / `tryAcquireDaily("currents", 225)` by
default. **Once a provider's effective cap is hit for the UTC day, zero
further requests go to it** — checked before the outbound call, same as
`isOpen` for the breaker. This is a hard stop, not a graceful degrade to
worse data: no request is ever sent past the cap.

## Caching — deliberately none, server-side

No domain record, no repository, no YAML file, no Postgres table, no
import-verifier entry, no published-page concern. The endpoint sets:

```
Cache-Control: private, max-age=86400
```

and the frontend calls it with a plain `fetch()` on a stable URL
(`/api/v1/trips/{id}/news`). A browser that already has a response for that
trip today never re-issues the request at all — no JS-level cache, no
localStorage, nothing to invalidate. This is a deliberate, narrow exception to
"derived values are never stored" (see CLAUDE.md): there is nothing to store,
because the data lives only as long as the browser's own HTTP cache keeps it,
the same 24 hours the quota is reset on.

Consequence: two different browsers (or the same browser after clearing
cache) loading the same trip the same day both count against the daily quota.
That's accepted — it's what "no caching, browser cache only" means — and the
quota's hard stop is what keeps it bounded regardless.

## Language

Resolved the same way as everything else localised: `RequestLocale` /
`User.languageCode`, falling back to English, passed straight through as
each API's `language=` parameter. Since only English ships today
(`messages_en.properties`, `js/i18n/en.js`), this is a no-op in practice, but
needs no new config — it already reads the member's setting once more
languages exist, the same as emails and published pages do.

## Response shape

```json
[
  {
    "destinationName": "Cusco",
    "countryCode": "PE",
    "countryFlag": "🇵🇪",
    "articles": [
      {
        "title": "...",
        "description": "...",
        "url": "https://...",
        "imageUrl": "https://...",
        "sourceName": "Andina",
        "publishedAt": "2026-09-26T05:30:00Z"
      }
    ]
  }
]
```

Capped at `app.news.max-articles-per-destination` (default 3, configurable —
e.g. 5) articles per destination group, bounding the carousel and the
response payload. A destination whose lookup was skipped (both providers
capped, or a genuine empty result) is simply absent from the array — the
frontend never sees "capped" vs "no news today" as different states.

## Frontend

- Lives in `js/pages/trip/destinations.js`, rendered below the destinations
  list, fetched lazily the first time the Destinations tab is opened in a
  page load (not on every trip bundle reload — this is a nice-to-have, unlike
  weather).
- **Carousel**: one card per destination group, each card showing image
  (fallback: a llama-emoji tile on a gradient background when no `imageUrl`),
  headline, source · relative time, arrow-nav (‹ ›) to scroll the row.
  Clicking a card opens `url` in a new tab (`target="_blank" rel="noopener"`),
  the same pattern as flight-pill map links.
- **Articles within each destination's group are shuffled client-side at
  render time** (Fisher-Yates over that group's array only — destinations
  themselves stay in the trip's own order, matching the list above them).
  This is purely a display concern: the API always returns the same
  (cached-for-24h) set and order, so without this every visit to the tab
  would show identical cards in identical positions all day. The shuffle
  re-runs on every render (tab switch, page reload), independent of the
  24-hour data cache underneath it — nothing about the shuffle is stored.
- If the trip has no destinations, or the response array is empty (both
  providers capped/erroring, or genuinely nothing found for every
  destination), **the whole panel doesn't render** — no "unavailable" message.
  This is a nice-to-have panel, not load-bearing like weather, so it fails
  silently rather than adding another empty-state string to translate.

## Documentation

Per CLAUDE.md's external-API rule: `docs/external-apis/newsdata.md` and
`docs/external-apis/currents.md`, each covering what/auth/base URL/config
keys/each call/trigger/frequency/caching/failure behaviour/quirks — the
country-vs-place-name relevance quirk and the La Paz ambiguity fix belong
here, plus a row in `docs/external-apis/README.md` for each.
