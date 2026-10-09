INSERT INTO "PlatformSetting" ("key", "value", "updatedBy", "updatedAt")
VALUES
  ('feature.live', '{"enabled":false}'::jsonb, NULL, CURRENT_TIMESTAMP),
  ('feature.needs', '{"enabled":false}'::jsonb, NULL, CURRENT_TIMESTAMP)
ON CONFLICT ("key") DO UPDATE
SET "value" = EXCLUDED."value",
    "updatedBy" = NULL,
    "updatedAt" = CURRENT_TIMESTAMP;
