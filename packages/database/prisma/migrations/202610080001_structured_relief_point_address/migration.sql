ALTER TABLE "ReliefPoint"
  ADD COLUMN "addressLine1" TEXT,
  ADD COLUMN "locality" TEXT,
  ADD COLUMN "landmark" TEXT,
  ADD COLUMN "postalCode" TEXT,
  ADD COLUMN "city" TEXT,
  ADD COLUMN "district" TEXT,
  ADD COLUMN "state" TEXT,
  ADD COLUMN "country" TEXT DEFAULT 'India';
