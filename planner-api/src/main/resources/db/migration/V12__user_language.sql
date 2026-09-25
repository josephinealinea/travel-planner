-- The language a member reads the app in, a code that has a messages file.
-- Nullable on purpose: no value means "whatever the browser asks for, else
-- English", which is not the same as having chosen English.
ALTER TABLE users ADD COLUMN language_code text;
