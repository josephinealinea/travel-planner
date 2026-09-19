-- Who is going to each part of a trip. See trips.api.Travellers.
--
-- Nullable with no default, unlike budget_items.shared_by_user_ids: NULL means
-- "not set, same as the parent" and '{}' means "explicitly the whole trip".
-- Those are two different states, and a NOT NULL DEFAULT '{}' column would
-- silently turn every "follow Cusco" into "everyone".
ALTER TABLE destinations    ADD COLUMN traveller_ids text[];
ALTER TABLE checklist_items ADD COLUMN traveller_ids text[];
ALTER TABLE itinerary_items ADD COLUMN traveller_ids text[];
