ALTER TABLE "ChatParticipant" ADD COLUMN "lastSyncAt" TIMESTAMP(3);
ALTER TABLE "ChatSyncNonce" ADD COLUMN "response" JSONB;
