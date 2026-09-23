-- Rename destinations.notes -> note to align with every other note field in the schema
-- (checklist_items.note, publish_requests.note are already singular).
ALTER TABLE destinations RENAME COLUMN notes TO note;

-- Add note to itinerary and budget items.
ALTER TABLE itinerary_items ADD COLUMN note text;
ALTER TABLE budget_items    ADD COLUMN note text;
