-- The baseline schema for database mode (feature-enable-database=true).
--
-- Flyway owns the schema and only the schema: no migration here ever inserts,
-- updates or deletes a row. Data arrives through the application — the
-- one-off YAML import included — never through a migration.
--
-- Rules this file follows, so the next migration can too:
--   * Nullable unless structurally required. YAML lets any field be absent,
--     and null already means something in places (all_day, lodging_seeded).
--   * A primitive boolean in the domain is NOT NULL DEFAULT false.
--   * Foreign keys only where the app guarantees the target exists: trip_id ->
--     trips ON DELETE CASCADE, and owner / members -> users. Cross-row links
--     (itinerary <-> budget, checklist <-> itinerary, seeded_from_destination_id,
--     payer, sharers, audit ids) get none, because the app deliberately keeps
--     them after their target is gone ("Former member", unlinking on
--     destination delete, adoptOrphanedDays).
--   * Enums are text with no CHECK — a check would be one more place to edit
--     when adding a category.
--   * Every per-trip table is keyed (trip_id, id), because ids are only unique
--     within a trip (weather ids repeat across trips by design), and carries
--     seq: insertion order, the only tie-break budget and weather have, and
--     what replaceAll's "the list becomes exactly this" writes into.
--   * The cascade makes "deleting a trip deletes everything it put anywhere" a
--     property of the schema. The published page is still removed by
--     TripService.delete.

CREATE TABLE users (
    id                        text PRIMARY KEY,
    email                     text NOT NULL UNIQUE,   -- stored normalised, as YAML does
    screen_name               text,
    password_hash             text,
    must_change_password      boolean NOT NULL DEFAULT false,
    currencies                text[]  NOT NULL DEFAULT '{}',
    display_currency          text,
    publish_itinerary_cost    boolean NOT NULL DEFAULT false,
    publish_destination_days  boolean NOT NULL DEFAULT false,
    publish_forecast_expenses boolean NOT NULL DEFAULT false,
    publish_personal_budget   boolean NOT NULL DEFAULT false,
    created_at                timestamptz,
    updated_at                timestamptz
);

CREATE TABLE trips (
    id                 text PRIMARY KEY,
    slug               text NOT NULL UNIQUE,
    title              text,
    start_date         date,
    end_date           date,
    owner_user_id      text REFERENCES users (id),
    status             text NOT NULL DEFAULT 'DRAFT',
    display_currency   text,
    published_theme    text,
    published_at       timestamptz,
    created_at         timestamptz,
    updated_at         timestamptz,
    created_by_user_id text,
    updated_by_user_id text
    -- Trip.exchangeRates deliberately absent: "no longer read", kept only so
    -- old YAML loads. A database has no old files.
);

CREATE TABLE trip_members (
    trip_id            text    NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    user_id            text    NOT NULL REFERENCES users (id),
    position           integer NOT NULL,   -- decides who gets the odd cent
    email              text,
    role               text    NOT NULL DEFAULT 'MEMBER',
    invited_by_user_id text,
    invited_at         timestamptz,
    PRIMARY KEY (trip_id, user_id)
);
CREATE INDEX trip_members_user_id_idx ON trip_members (user_id);

CREATE TABLE publish_requests (
    trip_id              text    NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    id                   text    NOT NULL,
    position             integer NOT NULL,
    requested_by_user_id text,
    status               text    NOT NULL DEFAULT 'PENDING',
    note                 text,
    theme                text,
    requested_at         timestamptz,
    decided_at           timestamptz,
    decided_by_user_id   text,
    PRIMARY KEY (trip_id, id)
);

CREATE TABLE destinations (
    trip_id            text   NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    id                 text   NOT NULL,
    seq                bigint GENERATED ALWAYS AS IDENTITY,
    name               text,
    country_code       text,
    country_name       text,
    country_flag       text,
    latitude           double precision,
    longitude          double precision,
    geoname_id         bigint,
    timezone           text,
    start_date         date,
    end_date           date,
    notes              text,
    sort_order         integer NOT NULL DEFAULT 0,
    lodging_seeded     boolean,
    suppress_checklist boolean,
    created_at         timestamptz,
    created_by_user_id text,
    updated_at         timestamptz,
    updated_by_user_id text,
    PRIMARY KEY (trip_id, id)
);

CREATE TABLE checklist_items (
    trip_id                    text    NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    id                         text    NOT NULL,
    seq                        bigint  GENERATED ALWAYS AS IDENTITY,
    country_codes              text[]  NOT NULL DEFAULT '{}',
    seeded_from_destination_id text,
    category                   text    NOT NULL DEFAULT 'OTHERS',
    description                text,
    note                       text,
    status                     text    NOT NULL DEFAULT 'TODO',
    auto_seeded                boolean NOT NULL DEFAULT false,
    sort_order                 integer NOT NULL DEFAULT 0,
    completed_at               timestamptz,
    created_at                 timestamptz,
    created_by_user_id         text,
    updated_at                 timestamptz,
    updated_by_user_id         text,
    PRIMARY KEY (trip_id, id)
);

CREATE TABLE itinerary_items (
    trip_id            text   NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    id                 text   NOT NULL,
    seq                bigint GENERATED ALWAYS AS IDENTITY,
    checklist_item_id  text,
    plan_id            text,
    category           text   NOT NULL DEFAULT 'OTHERS',
    description        text,
    start_at           timestamp,          -- wall-clock at the destination: no time zone
    end_at             timestamp,
    all_day            boolean,
    cost               numeric,
    currency           text,
    budget_item_id     text,
    country_codes      text[] NOT NULL DEFAULT '{}',
    sort_order         integer NOT NULL DEFAULT 0,
    created_at         timestamptz,
    created_by_user_id text,
    updated_at         timestamptz,
    updated_by_user_id text,
    PRIMARY KEY (trip_id, id)
);

CREATE TABLE budget_items (
    trip_id            text   NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    id                 text   NOT NULL,
    seq                bigint GENERATED ALWAYS AS IDENTITY,  -- budget's only tie-break
    itinerary_item_id  text,
    category           text   NOT NULL DEFAULT 'OTHERS',
    description        text,
    amount             numeric,
    currency           text,
    date               date,
    country_codes      text[] NOT NULL DEFAULT '{}',
    shared_by_user_ids text[] NOT NULL DEFAULT '{}',        -- order matters: the odd cent
    paid_by_user_id    text,
    status             text   NOT NULL DEFAULT 'CONFIRMED', -- no status reads as a charge
    confirmed_at       timestamptz,
    created_at         timestamptz,
    created_by_user_id text,
    updated_at         timestamptz,
    updated_by_user_id text,
    PRIMARY KEY (trip_id, id)
);

CREATE TABLE weather_records (
    trip_id         text   NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    id              text   NOT NULL,          -- "lat,lon:date", repeats across trips
    seq             bigint GENERATED ALWAYS AS IDENTITY,
    latitude        double precision NOT NULL,
    longitude       double precision NOT NULL,
    date            date   NOT NULL,
    weather_code    integer,
    temperature_max double precision,
    temperature_min double precision,
    precipitation   double precision,
    source          text,
    fetched_at      timestamptz,
    PRIMARY KEY (trip_id, id)
);

CREATE TABLE rate_table (
    singleton  boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    base       text NOT NULL,
    date       text,
    fetched_at timestamptz
);

CREATE TABLE exchange_rates (
    currency text    PRIMARY KEY,
    position integer NOT NULL,
    rate     numeric NOT NULL
);
