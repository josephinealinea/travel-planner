-- Flight lookup: one document on the entry, three install-wide tables.
-- Schema only. None of the three tables references trips: they hold public
-- schedule facts and a call counter, not trip data, so deleting a trip leaves
-- them alone.

ALTER TABLE itinerary_items ADD COLUMN flight jsonb;

CREATE TABLE flight_records (
    flight_number       text        NOT NULL,
    departure_date      date        NOT NULL,
    schedule            jsonb,
    live                jsonb,
    schedule_fetched_at timestamptz,
    live_fetched_at     timestamptz,
    not_found           boolean     NOT NULL DEFAULT false,
    PRIMARY KEY (flight_number, departure_date)
);

CREATE TABLE codeshare_mappings (
    booked_number    text        PRIMARY KEY,
    operating_number text        NOT NULL,
    resolved_at      timestamptz NOT NULL
);

CREATE TABLE api_usage (
    service text    NOT NULL,
    month   text    NOT NULL,
    calls   integer NOT NULL DEFAULT 0,
    PRIMARY KEY (service, month)
);
