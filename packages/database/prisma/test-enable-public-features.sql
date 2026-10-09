-- E2E runs use an isolated, seeded test database. Production defaults remain off.
INSERT INTO "PlatformSetting" ("key", "value", "updatedBy", "updatedAt")
VALUES
  ('feature.live', '{"enabled":true}'::jsonb, NULL, CURRENT_TIMESTAMP),
  ('feature.needs', '{"enabled":true}'::jsonb, NULL, CURRENT_TIMESTAMP)
ON CONFLICT ("key") DO UPDATE
SET "value" = EXCLUDED."value",
    "updatedBy" = NULL,
    "updatedAt" = CURRENT_TIMESTAMP;
