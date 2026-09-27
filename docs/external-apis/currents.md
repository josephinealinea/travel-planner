# Currents

Free plan, no paid tier configured. The second of Llama Lookout's two
providers — see `docs/external-apis/newsdata.md` for the shared design
(neither is "primary"; the first lookup per request tries NewsData, every
later one is a coin flip, whichever isn't capped).

- **Auth:** `apiKey` query parameter, `NEWSCURRENTS_KEY`
  (`app.news.currents.key`).
- **Base URL:** `https://api.currentsapi.services`,
  `app.news.currents.base-url`.
- **Call:** `GET /v1/search?apiKey=&keywords=&language=`, same query
  discipline as NewsData (`"<destination name>" <country name>`).
- **Triggered by / frequency / caching:** identical to NewsData — see that
  file.
- **Daily limit:** 250 requests, install-wide, capped at 90% (225) by default.
- **Failure behaviour:** identical shape to NewsData — empty list, never an
  error, on any failure including a 200 whose `status` isn't `"ok"`.
- **Quirks:** **does not support multi-country batching at all** — a
  comma-separated `country` list (`country=PE,BO`) is a hard `400 Bad
  request`, one more reason country-level filtering was rejected in favour of
  a place-name search. `published` carries an explicit UTC offset
  (`"2026-09-26 05:30:00 +0000"`), unlike NewsData's bare `pubDate`.
