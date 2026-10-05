-- CreateSchema
CREATE SCHEMA IF NOT EXISTS "public";

-- CreateEnum
CREATE TYPE "Role" AS ENUM ('PUBLIC', 'VOLUNTEER', 'COORDINATOR', 'ADMIN');

-- CreateEnum
CREATE TYPE "RequestStatus" AS ENUM ('DRAFT', 'OPEN', 'PARTIALLY_COMMITTED', 'FULLY_COMMITTED', 'CONFIRMED', 'IN_TRANSIT', 'PARTIALLY_RECEIVED', 'COMPLETED', 'CANCELLED', 'EXPIRED');

-- CreateEnum
CREATE TYPE "CommitmentStatus" AS ENUM ('RESERVED', 'ORDER_PLACED', 'VOLUNTEER_CONFIRMED', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED', 'EXPIRED');

-- CreateEnum
CREATE TYPE "ModerationState" AS ENUM ('PENDING', 'APPROVED', 'REJECTED', 'HIDDEN');

-- CreateEnum
CREATE TYPE "Category" AS ENUM ('FOOD', 'WATER', 'MEDICAL', 'HYGIENE', 'CLOTHING', 'POWER', 'SHELTER', 'OTHER');

-- CreateEnum
CREATE TYPE "Priority" AS ENUM ('NORMAL', 'HIGH', 'URGENT');

-- CreateTable
CREATE TABLE "User" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "displayName" TEXT NOT NULL,
    "passwordHash" TEXT NOT NULL,
    "role" "Role" NOT NULL DEFAULT 'PUBLIC',
    "active" BOOLEAN NOT NULL DEFAULT true,
    "emailVerifiedAt" TIMESTAMP(3),
    "totpSecret" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "User_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Session" (
    "id" TEXT NOT NULL,
    "tokenHash" TEXT NOT NULL,
    "csrfHash" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "label" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "revokedAt" TIMESTAMP(3),

    CONSTRAINT "Session_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Organization" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "verified" BOOLEAN NOT NULL DEFAULT false,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Organization_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "OrganizationMembership" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "role" "Role" NOT NULL,
    "approved" BOOLEAN NOT NULL DEFAULT false,

    CONSTRAINT "OrganizationMembership_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "VolunteerProfile" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "approvedAt" TIMESTAMP(3),
    "suspendedAt" TIMESTAMP(3),

    CONSTRAINT "VolunteerProfile_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "ReliefPoint" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "description" TEXT NOT NULL,
    "publicLocation" TEXT NOT NULL,
    "instructions" TEXT NOT NULL,
    "operatingHours" TEXT NOT NULL,
    "privacyLevel" TEXT NOT NULL DEFAULT 'APPROXIMATE',
    "latitude" DOUBLE PRECISION,
    "longitude" DOUBLE PRECISION,
    "exactLocationApproved" BOOLEAN NOT NULL DEFAULT false,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "organizationId" TEXT NOT NULL,

    CONSTRAINT "ReliefPoint_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "ReliefRequest" (
    "id" TEXT NOT NULL,
    "publicId" TEXT NOT NULL,
    "creatorId" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "reliefPointId" TEXT NOT NULL,
    "category" "Category" NOT NULL,
    "title" TEXT NOT NULL,
    "description" TEXT NOT NULL,
    "requestedQuantity" INTEGER NOT NULL,
    "committedQuantity" INTEGER NOT NULL DEFAULT 0,
    "receivedQuantity" INTEGER NOT NULL DEFAULT 0,
    "unit" TEXT NOT NULL,
    "priority" "Priority" NOT NULL DEFAULT 'NORMAL',
    "status" "RequestStatus" NOT NULL DEFAULT 'OPEN',
    "version" INTEGER NOT NULL DEFAULT 1,
    "deadline" TIMESTAMP(3) NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "archivedAt" TIMESTAMP(3),

    CONSTRAINT "ReliefRequest_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "ReliefRequestRevision" (
    "id" TEXT NOT NULL,
    "requestId" TEXT NOT NULL,
    "version" INTEGER NOT NULL,
    "actorId" TEXT NOT NULL,
    "snapshot" JSONB NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ReliefRequestRevision_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "DonationCommitment" (
    "id" TEXT NOT NULL,
    "requestId" TEXT NOT NULL,
    "quantity" INTEGER NOT NULL,
    "receivedQuantity" INTEGER NOT NULL DEFAULT 0,
    "email" TEXT,
    "tokenHash" TEXT NOT NULL,
    "status" "CommitmentStatus" NOT NULL DEFAULT 'RESERVED',
    "provider" TEXT,
    "externalOrderId" TEXT,
    "eta" TIMESTAMP(3),
    "notes" TEXT,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "DonationCommitment_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Delivery" (
    "id" TEXT NOT NULL,
    "commitmentId" TEXT NOT NULL,
    "quantity" INTEGER NOT NULL,
    "confirmedBy" TEXT NOT NULL,
    "receivedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Delivery_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "FieldUpdate" (
    "id" TEXT NOT NULL,
    "caption" TEXT NOT NULL,
    "authorId" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "reliefPointId" TEXT NOT NULL,
    "requestId" TEXT,
    "moderation" "ModerationState" NOT NULL DEFAULT 'PENDING',
    "publishAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "FieldUpdate_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "MediaAsset" (
    "id" TEXT NOT NULL,
    "ownerId" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "fieldUpdateId" TEXT,
    "originalKey" TEXT NOT NULL,
    "publicKey" TEXT,
    "thumbnailKey" TEXT,
    "mimeType" TEXT NOT NULL,
    "size" INTEGER NOT NULL,
    "moderation" "ModerationState" NOT NULL DEFAULT 'PENDING',
    "processingState" TEXT NOT NULL DEFAULT 'PENDING',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "MediaAsset_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Notification" (
    "id" TEXT NOT NULL,
    "kind" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "payload" JSONB NOT NULL,
    "deduplicationKey" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'PENDING',
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "nextAttemptAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "sentAt" TIMESTAMP(3),
    "lastError" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Notification_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "AuditEvent" (
    "id" TEXT NOT NULL,
    "actorId" TEXT,
    "event" TEXT NOT NULL,
    "entityType" TEXT NOT NULL,
    "entityId" TEXT NOT NULL,
    "previous" JSONB,
    "next" JSONB,
    "deviceId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "AuditEvent_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Device" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "publicKey" TEXT NOT NULL,
    "revokedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Device_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "OfflineEvent" (
    "id" TEXT NOT NULL,
    "authorId" TEXT NOT NULL,
    "organizationId" TEXT NOT NULL,
    "deviceId" TEXT NOT NULL,
    "protocolVersion" INTEGER NOT NULL,
    "type" TEXT NOT NULL,
    "payloadHash" TEXT NOT NULL,
    "payload" JSONB NOT NULL,
    "signature" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "invalidatedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "OfflineEvent_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "SyncReceipt" (
    "id" TEXT NOT NULL,
    "eventId" TEXT NOT NULL,
    "transportDeviceId" TEXT NOT NULL,
    "status" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "SyncReceipt_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "ModerationReport" (
    "id" TEXT NOT NULL,
    "entityType" TEXT NOT NULL,
    "entityId" TEXT NOT NULL,
    "reason" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'OPEN',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ModerationReport_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "IdempotencyRecord" (
    "id" TEXT NOT NULL,
    "scope" TEXT NOT NULL,
    "key" TEXT NOT NULL,
    "requestHash" TEXT NOT NULL,
    "response" JSONB NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "IdempotencyRecord_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "LoginAttempt" (
    "key" TEXT NOT NULL,
    "failures" INTEGER NOT NULL DEFAULT 0,
    "lockedUntil" TIMESTAMP(3),
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "LoginAttempt_pkey" PRIMARY KEY ("key")
);

-- CreateIndex
CREATE UNIQUE INDEX "User_email_key" ON "User"("email");

-- CreateIndex
CREATE UNIQUE INDEX "Session_tokenHash_key" ON "Session"("tokenHash");

-- CreateIndex
CREATE INDEX "Session_userId_expiresAt_idx" ON "Session"("userId", "expiresAt");

-- CreateIndex
CREATE UNIQUE INDEX "Organization_name_key" ON "Organization"("name");

-- CreateIndex
CREATE INDEX "OrganizationMembership_organizationId_approved_idx" ON "OrganizationMembership"("organizationId", "approved");

-- CreateIndex
CREATE UNIQUE INDEX "OrganizationMembership_userId_organizationId_key" ON "OrganizationMembership"("userId", "organizationId");

-- CreateIndex
CREATE UNIQUE INDEX "VolunteerProfile_userId_key" ON "VolunteerProfile"("userId");

-- CreateIndex
CREATE INDEX "ReliefPoint_organizationId_active_idx" ON "ReliefPoint"("organizationId", "active");

-- CreateIndex
CREATE UNIQUE INDEX "ReliefRequest_publicId_key" ON "ReliefRequest"("publicId");

-- CreateIndex
CREATE INDEX "ReliefRequest_status_priority_deadline_idx" ON "ReliefRequest"("status", "priority", "deadline");

-- CreateIndex
CREATE INDEX "ReliefRequest_organizationId_createdAt_idx" ON "ReliefRequest"("organizationId", "createdAt");

-- CreateIndex
CREATE UNIQUE INDEX "ReliefRequestRevision_requestId_version_key" ON "ReliefRequestRevision"("requestId", "version");

-- CreateIndex
CREATE UNIQUE INDEX "DonationCommitment_tokenHash_key" ON "DonationCommitment"("tokenHash");

-- CreateIndex
CREATE INDEX "DonationCommitment_status_expiresAt_idx" ON "DonationCommitment"("status", "expiresAt");

-- CreateIndex
CREATE INDEX "DonationCommitment_requestId_status_idx" ON "DonationCommitment"("requestId", "status");

-- CreateIndex
CREATE INDEX "FieldUpdate_moderation_publishAt_idx" ON "FieldUpdate"("moderation", "publishAt");

-- CreateIndex
CREATE INDEX "MediaAsset_organizationId_moderation_idx" ON "MediaAsset"("organizationId", "moderation");

-- CreateIndex
CREATE UNIQUE INDEX "Notification_deduplicationKey_key" ON "Notification"("deduplicationKey");

-- CreateIndex
CREATE INDEX "Notification_status_nextAttemptAt_idx" ON "Notification"("status", "nextAttemptAt");

-- CreateIndex
CREATE INDEX "AuditEvent_entityType_entityId_createdAt_idx" ON "AuditEvent"("entityType", "entityId", "createdAt");

-- CreateIndex
CREATE INDEX "AuditEvent_createdAt_idx" ON "AuditEvent"("createdAt");

-- CreateIndex
CREATE UNIQUE INDEX "SyncReceipt_eventId_transportDeviceId_key" ON "SyncReceipt"("eventId", "transportDeviceId");

-- CreateIndex
CREATE INDEX "ModerationReport_status_createdAt_idx" ON "ModerationReport"("status", "createdAt");

-- CreateIndex
CREATE UNIQUE INDEX "IdempotencyRecord_scope_key_key" ON "IdempotencyRecord"("scope", "key");

-- AddForeignKey
ALTER TABLE "Session" ADD CONSTRAINT "Session_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "OrganizationMembership" ADD CONSTRAINT "OrganizationMembership_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "OrganizationMembership" ADD CONSTRAINT "OrganizationMembership_organizationId_fkey" FOREIGN KEY ("organizationId") REFERENCES "Organization"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "VolunteerProfile" ADD CONSTRAINT "VolunteerProfile_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ReliefPoint" ADD CONSTRAINT "ReliefPoint_organizationId_fkey" FOREIGN KEY ("organizationId") REFERENCES "Organization"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ReliefRequest" ADD CONSTRAINT "ReliefRequest_creatorId_fkey" FOREIGN KEY ("creatorId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ReliefRequest" ADD CONSTRAINT "ReliefRequest_organizationId_fkey" FOREIGN KEY ("organizationId") REFERENCES "Organization"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ReliefRequest" ADD CONSTRAINT "ReliefRequest_reliefPointId_fkey" FOREIGN KEY ("reliefPointId") REFERENCES "ReliefPoint"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ReliefRequestRevision" ADD CONSTRAINT "ReliefRequestRevision_requestId_fkey" FOREIGN KEY ("requestId") REFERENCES "ReliefRequest"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "DonationCommitment" ADD CONSTRAINT "DonationCommitment_requestId_fkey" FOREIGN KEY ("requestId") REFERENCES "ReliefRequest"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Delivery" ADD CONSTRAINT "Delivery_commitmentId_fkey" FOREIGN KEY ("commitmentId") REFERENCES "DonationCommitment"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "FieldUpdate" ADD CONSTRAINT "FieldUpdate_authorId_fkey" FOREIGN KEY ("authorId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "FieldUpdate" ADD CONSTRAINT "FieldUpdate_organizationId_fkey" FOREIGN KEY ("organizationId") REFERENCES "Organization"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "FieldUpdate" ADD CONSTRAINT "FieldUpdate_reliefPointId_fkey" FOREIGN KEY ("reliefPointId") REFERENCES "ReliefPoint"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "FieldUpdate" ADD CONSTRAINT "FieldUpdate_requestId_fkey" FOREIGN KEY ("requestId") REFERENCES "ReliefRequest"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MediaAsset" ADD CONSTRAINT "MediaAsset_fieldUpdateId_fkey" FOREIGN KEY ("fieldUpdateId") REFERENCES "FieldUpdate"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Device" ADD CONSTRAINT "Device_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "SyncReceipt" ADD CONSTRAINT "SyncReceipt_eventId_fkey" FOREIGN KEY ("eventId") REFERENCES "OfflineEvent"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
