-- Where a member calls home, shown beside them on the Travel Buddies tab.
-- Nullable with no default: existing accounts start blank, like screen_name.
ALTER TABLE users ADD COLUMN home_country text;
