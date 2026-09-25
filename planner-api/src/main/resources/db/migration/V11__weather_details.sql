-- Extra daily readings (sunrise, UV, wind, ...) as one document rather than a
-- column each, so the next field is a key and not a migration. NULL for every
-- record cached before this existed: absent means "never fetched with details".
ALTER TABLE weather_records ADD COLUMN details jsonb;
