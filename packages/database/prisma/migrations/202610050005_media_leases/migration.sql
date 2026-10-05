ALTER TABLE "MediaAsset" ADD COLUMN "processingLease" TEXT,
  ADD COLUMN "processingStartedAt" TIMESTAMP(3),
  ADD COLUMN "processingAttempts" INTEGER NOT NULL DEFAULT 0;
CREATE INDEX "MediaAsset_processingState_processingStartedAt_idx"
  ON "MediaAsset"("processingState", "processingStartedAt");
