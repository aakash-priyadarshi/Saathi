ALTER TABLE "ChatMembership" DROP CONSTRAINT "ChatMembership_role_check";
ALTER TABLE "ChatMembership" ADD CONSTRAINT "ChatMembership_role_check"
  CHECK ("role" IN ('OWNER', 'ADMIN', 'MODERATOR', 'MEMBER', 'READ_ONLY'));
