-- Settlement payments: money one trip member handed another to settle a debt.
-- Its own table rather than a kind of budget row, so nothing that reads
-- budget_items can ever count a repayment as spending.
--
-- Keyed (trip_id, id) like every per-trip table, with seq recording insertion
-- order. Foreign key only to trips (cascade on delete); the two user ids have
-- none, because the app keeps a payment after either party has left the trip
-- and simply stops settling with them.
CREATE TABLE settlement_payments (
    trip_id            text   NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
    id                 text   NOT NULL,
    seq                bigint GENERATED ALWAYS AS IDENTITY,
    from_user_id       text,
    to_user_id         text,
    amount             numeric,
    currency           text,
    date               date,
    note               text,
    created_at         timestamptz,
    created_by_user_id text,
    updated_at         timestamptz,
    updated_by_user_id text,
    PRIMARY KEY (trip_id, id)
);
