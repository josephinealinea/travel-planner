-- The published page's theme is baked into the rendered file and chosen anew at
-- every publish, so nothing reads it back. A member's email lives on their user
-- record, which trip_members already references.
ALTER TABLE trips DROP COLUMN published_theme;
ALTER TABLE trip_members DROP COLUMN email;
