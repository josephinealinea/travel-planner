# Third-party notices

Travelling Llama's own code is under the [MIT licence](LICENSE). It also ships
or calls the third-party material below, each under its own terms.

## Shipped with the site

### Llama icon

- **What:** the Noto Emoji llama (U+1F999), used as `planner-web/favicon.svg`,
  `favicon.ico` and `apple-touch-icon.png`, and inlined as a data URI in every
  published page.
- **Copyright:** © Google LLC and the Noto Emoji authors.
- **Licence:** [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
- **Source:** <https://github.com/googlefonts/noto-emoji>
- **Changes:** `favicon.svg` is the unmodified source artwork. `favicon.ico` and
  `apple-touch-icon.png` are raster conversions of it (resized, PNG/ICO
  encoded).

### Alpine.js

- **What:** `planner-web/vendor/alpine.min.js`, version 3.17.3, unmodified.
- **Copyright:** © Caleb Porzio and contributors.
- **Licence:** [MIT](https://github.com/alpinejs/alpine/blob/main/LICENSE.md).
- **Source:** <https://github.com/alpinejs/alpine>

### Chart.js

- **What:** `planner-web/vendor/chart.umd.js`, version 4.5.1, unmodified. Its
  licence header is kept at the top of the file.
- **Copyright:** © 2025 Chart.js Contributors.
- **Licence:** [MIT](https://github.com/chartjs/Chart.js/blob/master/LICENSE.md).
- **Source:** <https://www.chartjs.org>

## Data services the site calls

These are not redistributed, but their free tiers carry terms. The technical
detail of each integration is in `docs/external-apis/`.

| Service | Terms to observe |
|---|---|
| [Open-Meteo](https://open-meteo.com/en/license) | Data is CC BY 4.0: credit Open-Meteo. The free API is for non-commercial use. Credited under the weather cards (planner and published page) and on Account. |
| [open.er-api.com](https://www.exchangerate-api.com/docs/free) | Free tier asks for attribution with a link back to exchangerate-api.com. Credited beside the budget total in the planner and on Account. Not on published pages: they never call it and carry only the converted figures. |
| [countries.dev](https://countries.dev) | Free, no key. Check its terms if usage grows. |

## Build and server dependencies

The API's Java dependencies (Spring Boot, Flyway, the AWS SDK and others) and
the frontend's build tools (Sass and others) are used under their own
open-source licences, mostly Apache 2.0 and MIT. They are not copied into this
repository. Their licences travel with the packages that Gradle and npm
download.
