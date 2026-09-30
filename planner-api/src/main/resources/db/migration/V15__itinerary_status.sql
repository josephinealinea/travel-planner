-- Pending itinerary proposals: FINAL is the default, matching every row
-- written before this field existed. approved_by_user_ids is an ordered
-- tally of who has clicked Approve, including the creator.
ALTER TABLE itinerary_items ADD COLUMN status text NOT NULL DEFAULT 'FINAL';
ALTER TABLE itinerary_items ADD COLUMN approved_by_user_ids text[];
