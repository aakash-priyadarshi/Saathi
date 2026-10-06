CREATE TABLE "ChatAttachment" (
  "id" UUID PRIMARY KEY, "messageId" UUID NOT NULL UNIQUE, "ownerId" TEXT NOT NULL,
  "manifest" JSONB NOT NULL, "size" INTEGER NOT NULL CHECK ("size" BETWEEN 29 AND 16777216),
  "hash" TEXT NOT NULL, "expiresAt" TIMESTAMP(3) NOT NULL
);
CREATE INDEX "ChatAttachment_expiresAt_idx" ON "ChatAttachment"("expiresAt");
CREATE INDEX "ChatAttachment_ownerId_idx" ON "ChatAttachment"("ownerId");
CREATE TABLE "ChatAttachmentChunk" (
  "attachmentId" UUID NOT NULL REFERENCES "ChatAttachment"("id") ON DELETE CASCADE,
  "part" INTEGER NOT NULL CHECK ("part" BETWEEN 0 AND 2047), "bytes" BYTEA NOT NULL CHECK (octet_length("bytes") BETWEEN 1 AND 8192),
  PRIMARY KEY ("attachmentId", "part")
);
