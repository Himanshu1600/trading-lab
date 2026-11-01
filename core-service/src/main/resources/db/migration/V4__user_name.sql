ALTER TABLE users ADD COLUMN name TEXT;

-- Accounts created before names existed start with the part of their email before the @.
UPDATE users SET name = split_part(email, '@', 1);

ALTER TABLE users ALTER COLUMN name SET NOT NULL;
