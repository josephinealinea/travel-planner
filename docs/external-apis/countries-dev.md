# countries.dev

Free, no key. Base URL `app.geocoding.base-url`, timeout 5 s. Code:
`geocoding/GeocodingClient`, `geocoding/CountryCatalog`.

| Call | Purpose | When | How often |
|---|---|---|---|
| `GET /cities?q=&limit=8` | destination autocomplete | member types in the destination name (min 2 chars) | one lookup per pause in typing; the browser debounces 250 ms (`LOOKUP_DEBOUNCE_MS`) |
| `GET /places?q=&limit=20` | same, small towns below the `/cities` population floor | same request as above | a second call per lookup, plus a third with the spaces removed when the query has spaces ("Machu Picchu" → "MachuPicchu") |
| `GET /countries` | the whole country list, for flag and currency | first time anything needs a country | **once per process**; retried only if it failed |
| `GET /alpha/{code}` | one country's flag/currency | a code the list did not cover | once per code, **cached forever in memory, including misses** |

So one autocomplete keystroke-pause costs 2 calls (3 with a space), and the
country data costs one call per API start. Nothing is written to disk; a
Cloud Run cold start re-fetches the country list on first use.

Where the result goes: `GET /api/v1/geocode?q=` returns suggestions with
coordinates, country code, flag and currency. Saving a destination stores the
country **code** and flag only (`DestinationService`); the code is named by
`planner-web/js/countries.js`, not by this API. `ItineraryService` reads a
country's primary currency to pre-fill a plan's cost, and the published-page
renderer reads it for the country cards.

Quirks that matter (details in `CLAUDE.md`): `/cities` answers **404 with plain
text** for no match, which is an empty result; `/places` also returns regions,
hotels and rivers, so only `featureClass` `P` is kept; an empty result is
normal and free text always saves.


Back to the [overview](README.md).
