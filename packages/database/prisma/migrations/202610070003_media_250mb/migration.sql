-- Chat attachments share the 250 MiB media limit (plus up to 1 MiB of encryption overhead).
ALTER TABLE "ChatAttachment" DROP CONSTRAINT "ChatAttachment_size_check";
ALTER TABLE "ChatAttachment" ADD CONSTRAINT "ChatAttachment_size_check" CHECK ("size" BETWEEN 29 AND 263192576);
