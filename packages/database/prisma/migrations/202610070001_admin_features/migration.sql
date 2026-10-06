ALTER TABLE "CommunityEvent"
  ADD COLUMN "moderation" "ModerationState" NOT NULL DEFAULT 'APPROVED';

UPDATE "CommunityEvent"
SET "moderation" = 'PENDING'
WHERE "type" = 'HELP'
  AND "hidden" = false
  AND "expiresAt" > CURRENT_TIMESTAMP;

CREATE INDEX "CommunityEvent_type_moderation_expiresAt_idx"
  ON "CommunityEvent"("type", "moderation", "expiresAt");

CREATE TABLE "PlatformSetting" (
  "key" TEXT NOT NULL,
  "value" JSONB NOT NULL,
  "updatedBy" TEXT,
  "updatedAt" TIMESTAMP(3) NOT NULL,
  CONSTRAINT "PlatformSetting_pkey" PRIMARY KEY ("key")
);

INSERT INTO "PlatformSetting" ("key", "value", "updatedAt")
VALUES ('feature.needs', '{"enabled":true}'::jsonb, CURRENT_TIMESTAMP);
