import { z } from 'zod';
import { chatProfileSchema, participantIdSchema, validChatProfile, type ChatMessage } from './chat';
import { bytes, hash, verify } from './crypto';
const signature = z.string().regex(/^[A-Za-z0-9_-]{86}$/);
export const chatAttachmentManifestSchema = z
  .object({
    body: z
      .object({
        v: z.literal(1),
        kind: z.literal('CHAT_ATTACHMENT'),
        id: z.string().uuid(),
        messageId: z.string().uuid(),
        messageHash: participantIdSchema,
        author: chatProfileSchema,
        size: z.number().int().min(29).max(16777216),
        cipherHash: participantIdSchema,
        expiresAt: z.string().datetime(),
      })
      .strict(),
    signature,
  })
  .strict();
export const chatAttachmentRequestSchema = z
  .object({
    body: z
      .object({
        v: z.literal(1),
        kind: z.literal('CHAT_ATTACHMENT_REQUEST'),
        profile: chatProfileSchema,
        issuedAt: z.string().datetime(),
        messageId: z.string().uuid(),
        manifest: chatAttachmentManifestSchema.nullable(),
        parts: z.array(z.number().int().min(0).max(2047)).max(6),
        chunks: z
          .array(
            z
              .object({
                part: z.number().int().min(0).max(2047),
                data: z
                  .string()
                  .regex(/^[A-Za-z0-9_-]+$/)
                  .max(10923),
              })
              .strict(),
          )
          .max(6),
      })
      .strict(),
    signature,
  })
  .strict();
export async function validChatAttachmentManifest(
  value: unknown,
  message: ChatMessage,
  now = Date.now(),
) {
  const manifest = chatAttachmentManifestSchema.parse(value),
    b = manifest.body;
  await validChatProfile(b.author, now);
  if (
    bytes(manifest).length > 3000 ||
    b.messageId !== message.body.id ||
    b.messageHash !== (await hash(message)) ||
    b.author.body.id !== message.body.author.body.id ||
    !['PHOTO', 'VIDEO', 'VOICE', 'FILE'].includes(message.body.format) ||
    Date.parse(b.expiresAt) > Date.parse(message.body.expiresAt) ||
    Date.parse(b.expiresAt) <= now ||
    !(await verify(b, manifest.signature, b.author.body.publicKey))
  )
    throw new Error('Invalid private attachment manifest.');
  return manifest;
}
