-- A member's home country is stored as its code alone, named by the shared
-- country table like every other country. The name column has no reader left.
ALTER TABLE users ADD COLUMN home_country_code text;
ALTER TABLE users DROP COLUMN home_country;
