CREATE TABLE "ChatAction" (
  "id" TEXT NOT NULL PRIMARY KEY,
  "conversationId" TEXT NOT NULL REFERENCES "ChatConversation"("id") ON DELETE CASCADE,
  "actorId" TEXT NOT NULL,
  "targetId" TEXT NOT NULL,
  "action" TEXT NOT NULL,
  "version" INTEGER NOT NULL,
  "envelope" JSONB NOT NULL,
  "envelopeHash" TEXT NOT NULL,
  "receivedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "expiresAt" TIMESTAMP(3) NOT NULL
);
CREATE INDEX "ChatAction_conversationId_receivedAt_id_idx" ON "ChatAction"("conversationId", "receivedAt", "id");
CREATE INDEX "ChatAction_conversationId_version_idx" ON "ChatAction"("conversationId", "version");
CREATE INDEX "ChatAction_expiresAt_idx" ON "ChatAction"("expiresAt");
