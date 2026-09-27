# NewsData.io

Free plan, no paid tier configured. Powers Llama Lookout, the news carousel
below the destinations list on the Destinations tab.

- **Auth:** `apikey` query parameter, `NEWSDATA_KEY` (`app.news.newsdata.key`).
- **Base URL:** `https://newsdata.io`, `app.news.newsdata.base-url`.
- **Call:** `GET /api/1/latest?apikey=&q=&language=`, one call per unique
  destination (`name` + `countryCode`) in a trip. `q` is always
  `"<destination name>" <country name>` — a bare place name is ambiguous (a
  live probe returned Mexico's La Paz, Baja California Sur, for a bare
  `q=La Paz` meant for Bolivia's capital) and `country=` filtering was tested
  and rejected: it returns generic wire-service stories tagged with 20–30
  countries at once, batched or not.
- **Triggered by:** a member opening the Destinations tab.
- **Frequency:** at most once per unique destination per tab-open; the
  frontend relies on the endpoint's own `Cache-Control: private, max-age=86400`
  so most opens cost the browser's HTTP cache, not a real request.
- **Caching:** none, server-side, on purpose. Nothing is written to disk or a
  table; the browser's HTTP cache is the only cache.
- **Daily limit:** 200 requests, install-wide, capped at 90% (180) by default
  (`app.news.cap-fraction`, `app.news.newsdata.daily-limit`). Once the cap is
  reached for the UTC day, zero further calls go out.
- **Failure behaviour:** disabled (no key), circuit-breaker-paused, capped, a
  non-200 response, or a 200 whose own `status` field isn't `"success"` all
  answer with an empty article list for that destination — never an error the
  member sees, and never merged with Currents' results for the same
  destination (see `NewsService`).
- **Quirks:** the free plan strips `content` (`"ONLY AVAILABLE IN PAID
  PLANS"`); only title, description, image, source name, publish date and link
  are used. `image_url` is frequently null.
