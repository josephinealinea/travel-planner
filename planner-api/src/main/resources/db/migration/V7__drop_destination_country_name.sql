-- A country is stored as its code alone. Every screen and the published page
-- resolve the name from planner-web/js/countries.js (mirrored in
-- publish/countries.json), so the copy kept on each destination was never read.
ALTER TABLE destinations DROP COLUMN country_name;
