-- How the planner's trip pages look to the member, one document so the next
-- setting is a key rather than a migration. Same shape users.yml writes.
-- Schema only: every account starts with both flags off.
ALTER TABLE users ADD COLUMN trip_pages jsonb NOT NULL
    DEFAULT '{"showWholeTrip": false, "maskAmounts": false}'::jsonb;
