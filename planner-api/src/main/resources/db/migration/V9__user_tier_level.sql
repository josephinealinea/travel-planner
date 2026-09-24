-- BASIC, PRO or ROCKSTAR. NOT NULL with a default so every existing account
-- reads as BASIC, which is what all of them were; the mapper still writes the
-- value itself, since an explicit NULL would not fall back to the default.
ALTER TABLE users ADD COLUMN tier_level text NOT NULL DEFAULT 'BASIC';
