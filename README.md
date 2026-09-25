# Travel Planner

Plan a trip with the people you are travelling with, then publish it as a public
page — modelled on the trip pages at [josephinealinea.dev](https://josephinealinea.dev/travel/).

- **`planner-api/`** — Spring Boot 3.5 / Java 21. YAML files by default, laid
  out like the `_data/travels/` folder on the personal site; PostgreSQL behind
  the `feature-enable-database` flag.
- **`planner-web/`** — static HTML, vanilla ES modules and Alpine.js. Sass is the
  only build step.

The unit of work is the **checklist**. Adding a destination seeds three items for
it, planning one produces itinerary entries, and putting a cost on a plan
produces a budget record.

## Add to repo
```bash
git init -b main
```
```bash
git remote add origin https://github.com/josephinealinea/travel-planner.git
```

## Get it running

You need Java 21, Node (for Sass) and Python 3 (for the static server).

If either the API or the frontend is already running from a previous session,
starting it again fails with "port already in use" rather than restarting it.
Stop both first:

#### Stop everything
```bash
lsof -ti :8080 | xargs -r kill; lsof -ti :3000 | xargs -r kill
```
#### Stop Postgres
```bash
./docker-stop.sh
```
#### Start the API (yml mode ON)

cd planner-api && FEATURE_ENABLE_DATABASE=false && BOOTSTRAP_OWNER_EMAIL=you@example.com BOOTSTRAP_OWNER_PASSWORD=password123 ./gradlew bootRun

#### If database mode ON
#### Start Postgres
```bash
./docker-start.sh
```
#### Start the API in database mode
```bash
cd planner-api && FEATURE_ENABLE_DATABASE=true BOOTSTRAP_OWNER_EMAIL=you@example.com BOOTSTRAP_OWNER_PASSWORD=password123 ./gradlew bootRun
```
#### Build the stylesheets & serve the FE
```bash
cd planner-web && npm install && npm run css && ./serve.sh
```

#### Open it
```bash
open http://localhost:3000/login.html
```

Sign in with the bootstrap email and password you set above. There is no
self-signup: every other account comes into being when somebody adds you to a
trip.

## How a trip comes together

1. **Create a trip** — title, start date, end date.
2. **Add travel buddies** by email, on the **Travel Buddies** tab. Each one gets
   an account and an emailed temporary password; in local development the email
   is written to `planner-api/data/outbox/*.eml`, so you can read it. Until a
   buddy signs in and picks a screen name, the list shows their email address.
   (Everywhere below, and in the code, a travel buddy is a trip *member*.)
3. **Add destinations.** Typing a name looks the place up on
   [countries.dev](https://countries.dev) and fills in the country and
   coordinates, all of which stay editable — free text always saves.
   Each destination seeds three checklist items:

   ```
   Plan transportation to Cusco
   Plan 6N accommodation in Cusco     <- counted when both dates are set
   Plan activities in Cusco
   ```

4. **Say who's going.** A destination can be for some travel buddies only, and
   its checklist and plans follow it unless you change them. Each buddy sees
   their own part; the trip's owner can switch between **Mine** and
   **Whole trip**.
5. **Work the checklist.** Open an item to edit it, add a note, or press **Plan**
   to record a flight or booking — the form arrives pre-filled. Add as many
   plans as the job needs with **Plan another**; nothing is complete until
   somebody presses **Set this Checklist to Complete**.
6. **Costs become budget.** A cost on a plan creates a matching budget record.
   Editing the cost updates the amount; clearing it removes the record. Expenses
   can also be added by hand. Settle Expenses lists who owes whom pair by pair;
   set `SIMPLIFY_DEBTS=true` to net debts across the whole trip so fewer payments
   are needed (off by default).
   **Record payment** on a line of the Settle panel marks a debt (or part of one)
   as paid: it settles the balance without counting as spending, so no total
   changes, and it never appears on a published page.
7. **Publish.** The owner can publish at any point, however unfinished. Any other
   member gets **Request to Publish**, and the owner's approval publishes. Set `REQUIRE_OWNER_APPROVAL=false` to let any member publish and
   re-publish directly (only the owner can unpublish).

## What publishing produces

A single self-contained HTML file per trip, with its stylesheet, script and data
all inlined:

```
planner-api/data/published/<slug>/
  index.html      no external requests at all
  trip.json       the same data, for reuse
```

The API serves it at `/p/<slug>` with no authentication, but nothing depends on
that — the directory is a plain static site, so copying it to S3, Netlify or
GitHub Pages is the whole of "put it on a CDN". You can confirm that by opening
the file from any static server, with the API stopped.

## Languages

Every word the app says lives in a message file, one per language, and code
refers to keys. English is the default and the fallback: a language that is
missing a key shows English. A member picks their language in
Account → Appearance; it applies to the planner, the emails they receive and the
pages published for them.

To change what the app says, edit the English file. To add a language, copy the
English files and translate the values, leaving the keys alone. The API side
is `messages_<language>.properties`, and the web side is `js/i18n/<language>.js`.
A translation only needs the keys it changes.

#### Copy the API's English messages
```bash
cp planner-api/src/main/resources/messages_en.properties planner-api/src/main/resources/messages_es.properties
```
#### Copy the web's English messages
```bash
cp planner-web/js/i18n/en.js planner-web/js/i18n/es.js
```
Then, in the API file, name the language in itself so the picker can show it
(`language.name.es=Español`), and translate the values. In a message that takes
arguments write a literal apostrophe twice (`it''s`); the tests fail if you do not.

#### Check the web messages
```bash
cd planner-web && npm run check
```
#### Check the API messages
```bash
cd planner-api && ./gradlew test --tests 'MessageKeysTest'
```

A language appears in the picker as soon as its API file exists. The web file is
what makes the pages read in it; without one the pages stay English.

## Permissions

| Action | Who |
|---|---|
| Create a trip | anyone signed in — they become its owner |
| Edit destinations, checklist, itinerary, budget | any member |
| Add or remove a member | any member |
| Remove the owner | nobody |
| Remove yourself | allowed — that is how you leave a trip |
| Delete the whole trip | owner only |
| Publish or unpublish | owner only |
| Request to publish | any member who is not the owner |

## Storage, and the database flag

By default `feature-enable-database` is **off**, and every record is a YAML
file under `planner-api/data/`:

```
users.yml                            every account
trips/index.yml                      id -> slug directory
travels/trip/<slug>.yml              the trip, its members, its publish requests
travels/destinations/<slug>.yml
travels/checklist/<slug>.yml
travels/itinerary/<slug>.yml
travels/budget/<slug>.yml
published/<slug>/                    rendered public pages
outbox/                              sent email, in local development
```

The files are meant to be readable next to the hand-written ones on the personal
site — ISO dates, no document markers, optional fields simply absent.

**One instance only** in this mode. The file store is guarded by in-process
locks and atomic renames, which is enough for a single API but not for two.

### Database mode

Every repository is also backed by PostgreSQL, so the same app runs on a real
database — needed for more than one instance, and what the [Cloud Run
deployment](docs/deploy.md) uses. Locally, a Postgres 17 container
(`compose.yaml`) stands in for it. Start it, then point the API at it:

#### Start Postgres
```bash
./docker-start.sh
```
#### Start the API in database mode
```bash
cd planner-api && FEATURE_ENABLE_DATABASE=true BOOTSTRAP_OWNER_EMAIL=you@example.com BOOTSTRAP_OWNER_PASSWORD=password123 ./gradlew bootRun
```
#### Stop Postgres
```bash
./docker-stop.sh
```

`docker-stop.sh` leaves the container's data in place — `docker-start.sh` picks
up where it left off. To also wipe the data, run `docker compose down
--volumes` instead.

#### Run the tests
```bash
cd planner-api && ./gradlew test
```
#### Watch the stylesheets while working on the frontend
```bash
cd planner-web && npm run css:watch
```

