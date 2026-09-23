-- The published-page settings become one document instead of a column each,
-- so the next one is a key rather than a migration and four more places to
-- thread a boolean through. The shape matches what users.yml writes.
ALTER TABLE users ADD COLUMN published_page jsonb NOT NULL
    DEFAULT '{"itineraryCost": false, "destinationDays": false, "forecastExpenses": false}'::jsonb;

-- Schema only, so the old values are not carried across: these are four
-- checkboxes that default to off, and every account starts again from off.
ALTER TABLE users DROP COLUMN publish_itinerary_cost;
ALTER TABLE users DROP COLUMN publish_destination_days;
ALTER TABLE users DROP COLUMN publish_forecast_expenses;

-- "Publish my own page" is gone entirely: publishing a trip now writes every
-- member their own page, so there is nothing left to opt into.
ALTER TABLE users DROP COLUMN publish_personal_budget;
