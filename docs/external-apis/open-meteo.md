# Open-Meteo

Free, no key. Two hosts, because one cannot answer everything:

| Endpoint | Config | Serves |
|---|---|---|
| `https://api.open-meteo.com/v1/forecast` | `app.weather.forecast-base-url` | real forecast, about 85 days back to 14 days ahead |
| `https://climate-api.open-meteo.com/v1/climate` | `app.weather.climate-base-url` | model projection (`MRI_AGCM3_2_S`), used outside that window; labelled "Typical" on screen, never passed off as a forecast |

Both take `daily=` values, `timezone=auto` and **comma-separated coordinate
lists**, so a trip costs **at most two calls** (one per endpoint), never one per
destination. Timeout 8 s. A read timeout means Open-Meteo is throttling.

There are two independent callers:

**1. The API, for the planner** (`weather/WeatherClient`, `WeatherService`)

- Trigger: opening a trip. The frontend calls `GET /trips/{id}/weather` from
  `reload()`, which runs after every mutation, but only refetches when the
  destinations' signature (id, name, dates, coordinates) changed, so a
  checklist tick costs nothing.
- Only the gaps are asked: adding one destination looks up that destination.
- Cache rules, per day:
  - past date with a stored reading: **never** refetched;
  - past date with none: fetched once;
  - today: refetched after 12 h (`app.weather.today.cache-ttl`);
  - future day: refetched after 12 h (`app.weather.cache-ttl`).
- Readings are **saved only while the trip is under way** (its start to end
  date). Before that they are held in memory only, so a cold start re-asks.
- Worst case for a trip with a lookup pending: 2 calls per 12 h per trip, only
  while someone has the trip open.

**2. The reader's browser, for a published page** (`resources/publish/page.js`)

- Trigger: someone opens a published page. Cannot be baked in at publish time,
  since the file may be opened months later.
- One request per endpoint for the whole page, coordinates de-duplicated
  across days. **No caching**: every page view is 1-2 calls from that reader's
  own IP, so load scales with readers and never touches the API.


Back to the [overview](README.md).
