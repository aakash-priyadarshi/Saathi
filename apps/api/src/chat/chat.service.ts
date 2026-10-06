import { BadRequestException, ConflictException, ForbiddenException } from '@nestjs/common';
import { Prisma } from '@saathi/database';
import {
  bytes,
  chatSyncSchema,
  hash,
  verify,
  validChatProfile,
  validChannelPolicy,
  validChatMessage,
  validChatReceipt,
  validChatJoin,
  chatAttachmentRequestSchema,
  validChatAttachmentManifest,
  type ChatProfile,
  type ChannelPolicy,
  type ChatMessage,
} from '@saathi/protocol';
import { Database } from '../database';
type Tx = Prisma.TransactionClient;
const json = (value: unknown) => JSON.parse(JSON.stringify(value)) as Prisma.InputJsonValue;
const active = (policy: ChannelPolicy, id: string) =>
  !policy.body.deleted && policy.body.members.some((m) => m.profile.body.id === id && !m.removedAt);

/** Operational plane only. Chat signatures confer no relief/account authority. */
export class ChatService {
  constructor(private readonly db: Database) {}
  async attachment(input: unknown) {
    const request = chatAttachmentRequestSchema.parse(input),
      b = request.body,
      now = Date.now(),
      id = b.profile.body.id;
    if (bytes(request).length > 90000 || Math.abs(Date.parse(b.issuedAt) - now) > 300000)
      throw new BadRequestException('Private attachment request bounds exceeded.');
    try {
      await validChatProfile(b.profile, now);
      if (!(await verify(b, request.signature, b.profile.body.publicKey))) throw new Error();
    } catch {
      throw new ForbiddenException('Private attachment signature is invalid.');
    }
    if (
      new Set(b.parts).size !== b.parts.length ||
      new Set(b.chunks.map((c) => c.part)).size !== b.chunks.length
    )
      throw new BadRequestException('Duplicate attachment parts.');
    // Cleanup commits independently even if a subsequent read is denied or the upload is incomplete.
    await this.db.chatAttachment.deleteMany({ where: { expiresAt: { lte: new Date(now) } } });
    return this.db.$transaction(
      async (tx) => {
        // Serialize quota reservations and membership with the same conversation lock used by sync.
        const row = await tx.chatMessage.findUnique({
          where: { id: b.messageId },
          include: { conversation: true },
        });
        if (!row || row.expiresAt.getTime() <= now)
          throw new ForbiddenException('Attachment message is unavailable.');
        await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${'conv:' + row.conversationId},0))`;
        const member = await tx.chatMembership.findUnique({
          where: {
            conversationId_participantId: { conversationId: row.conversationId, participantId: id },
          },
        });
        const conversation = await tx.chatConversation.findUniqueOrThrow({
          where: { id: row.conversationId },
        });
        if (!member || member.removedAt || conversation.deleted)
          throw new ForbiddenException('Attachment membership is unavailable.');
        const message = row.envelope as unknown as ChatMessage;
        if (
          await tx.chatBlock.findFirst({
            where: {
              OR: [
                { participantId: id, blockedId: row.authorId },
                { participantId: row.authorId, blockedId: id },
                ...(message.body.recipientId
                  ? [
                      { participantId: id, blockedId: message.body.recipientId },
                      { participantId: message.body.recipientId, blockedId: id },
                    ]
                  : []),
              ],
            },
          })
        )
          throw new ForbiddenException('Private attachment delivery is blocked.');
        if (conversation.visibility !== 'DIRECT') {
          const policy = conversation.policy as unknown as ChannelPolicy;
          if (Date.parse(policy.body.expiresAt) <= now || !active(policy, id))
            throw new ForbiddenException('Refresh channel membership first.');
          const history = await tx.chatPolicy.findUnique({
            where: { id: message.body.policyHash! },
          });
          if (
            !history ||
            (conversation.visibility === 'INVITE' &&
              !active(history.policy as unknown as ChannelPolicy, id))
          )
            throw new ForbiddenException('Earlier private history is unavailable to this member.');
        }
        await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended('chat-attachment-quota',0))`;
        await tx.chatAttachment.deleteMany({ where: { expiresAt: { lte: new Date(now) } } });
        let attachment = await tx.chatAttachment.findUnique({ where: { messageId: row.id } });
        if (b.manifest) {
          let manifest;
          try {
            manifest = await validChatAttachmentManifest(b.manifest, message, now);
          } catch {
            throw new ForbiddenException('Private attachment manifest is invalid.');
          }
          if (attachment && (await hash(attachment.manifest)) !== (await hash(manifest)))
            throw new ConflictException('Attachment identifier was already used.');
          if (!attachment) {
            const own = await tx.chatAttachment.aggregate({
              where: { ownerId: row.authorId },
              _sum: { size: true },
              _count: true,
            });
            const total = await tx.chatAttachment.aggregate({ _sum: { size: true } });
            if (
              own._count >= 50 ||
              (own._sum.size ?? 0) + manifest.body.size > 64 * 1048576 ||
              (total._sum.size ?? 0) + manifest.body.size > 512 * 1048576
            )
              throw new BadRequestException('Private attachment storage quota reached.');
            if (await tx.chatAttachment.findUnique({ where: { id: manifest.body.id } }))
              throw new ConflictException('Attachment identifier was already used.');
            attachment = await tx.chatAttachment.create({
              data: {
                id: manifest.body.id,
                messageId: row.id,
                ownerId: row.authorId,
                manifest: json(manifest),
                size: manifest.body.size,
                hash: manifest.body.cipherHash,
                expiresAt: new Date(manifest.body.expiresAt),
              },
            });
          }
        }
        if (!attachment)
          throw new BadRequestException('The sender has not synchronized this attachment yet.');
        const count = Math.ceil(attachment.size / 8192);
        for (const chunk of b.chunks) {
          const data = Buffer.from(chunk.data, 'base64url');
          if (
            chunk.part >= count ||
            data.toString('base64url') !== chunk.data ||
            data.length !== Math.min(8192, attachment.size - chunk.part * 8192)
          )
            throw new BadRequestException('Invalid attachment chunk.');
          const old = await tx.chatAttachmentChunk.findUnique({
            where: { attachmentId_part: { attachmentId: attachment.id, part: chunk.part } },
          });
          if (old && !Buffer.from(old.bytes).equals(data))
            throw new ConflictException('Attachment chunk conflicts with saved bytes.');
          if (!old)
            await tx.chatAttachmentChunk.create({
              data: { attachmentId: attachment.id, part: chunk.part, bytes: data },
            });
        }
        if (b.parts.some((part) => part >= count))
          throw new BadRequestException('Invalid attachment part.');
        const received = await tx.chatAttachmentChunk.findMany({
          where: { attachmentId: attachment.id },
          select: { part: true },
          orderBy: { part: 'asc' },
        });
        const chunks = await tx.chatAttachmentChunk.findMany({
          where: { attachmentId: attachment.id, part: { in: b.parts } },
          orderBy: { part: 'asc' },
        });
        // Completeness here denotes opaque parts. Recipients verify the signed cipher hash and AEAD.
        return {
          v: 1,
          manifest: attachment.manifest,
          received: received.map((c) => c.part),
          chunks: chunks.map((c) => ({
            part: c.part,
            data: Buffer.from(c.bytes).toString('base64url'),
          })),
          complete: received.length === count,
        };
      },
      { timeout: 20000, maxWait: 15000 },
    );
  }
  private async profile(tx: Tx, profile: ChatProfile) {
    const old = await tx.chatParticipant.findUnique({ where: { id: profile.body.id } });
    if (old) {
      const saved = old.profile as unknown as ChatProfile;
      if ((await hash(saved.body.encryptionKey)) !== (await hash(profile.body.encryptionKey)))
        throw new ConflictException('Chat encryption identity changed.');
      if (Date.parse(saved.body.updatedAt) > Date.parse(profile.body.updatedAt)) return;
    }
    await tx.chatParticipant.upsert({
      where: { id: profile.body.id },
      create: { id: profile.body.id, profile: json(profile) },
      update: { profile: json(profile) },
    });
  }
  async sync(input: unknown) {
    const request = chatSyncSchema.parse(input),
      body = request.body,
      now = Date.now(),
      id = body.profile.body.id;
    if (bytes(request).length > 90000 || Math.abs(Date.parse(body.issuedAt) - now) > 300000)
      throw new BadRequestException('Chat request is too large or its clock is out of date.');
    try {
      await validChatProfile(body.profile, now);
      if (!(await verify(body, request.signature, body.profile.body.publicKey))) throw new Error();
      for (const peer of body.peers) await validChatProfile(peer, now);
      for (const policy of body.policies) await validChannelPolicy(policy, now);
      for (const join of body.joins) await validChatJoin(join, now);
    } catch {
      throw new ForbiddenException('Chat signature or authorization is invalid.');
    }
    return this.db.$transaction(
      async (tx) => {
        // Sorted advisory locks serialize membership, duplicate IDs and nonce reuse.
        const locks = new Set([
          'actor:' + id,
          'nonce:' + body.id,
          ...body.policies.map((p) => 'conv:' + p.body.id),
          ...body.messages.map((m) => 'conv:' + m.body.conversationId),
          ...body.joins.map((j) => 'conv:' + j.body.channelId),
          ...body.messages.map((m) => 'message:' + m.body.id),
          ...[
            body.profile,
            ...body.peers,
            ...body.policies.flatMap((p) => p.body.members.map((m) => m.profile)),
            ...body.messages.map((m) => m.body.author),
          ].map((p) => 'profile:' + p.body.id),
        ]);
        for (const lock of [...locks].sort())
          await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${lock},0))`;
        const requestHash = await hash(request),
          nonce = await tx.chatSyncNonce.findUnique({ where: { id: body.id } });
        if (nonce && (nonce.participantId !== id || nonce.hash !== requestHash))
          throw new ConflictException('Chat request identifier was already used.');
        if (nonce?.response) return nonce.response;
        const actor = await tx.chatParticipant.findUnique({ where: { id } });
        if (actor?.lastSyncAt && actor.lastSyncAt.getTime() >= Date.parse(body.issuedAt))
          throw new ConflictException('This chat request was superseded. Check again.');
        await this.profile(tx, body.profile);
        for (const profile of body.peers) await this.profile(tx, profile);
        const accepted: string[] = [],
          rejected: { id: string; reason: string }[] = [],
          acceptedReceipts: string[] = [],
          acceptedPolicies: string[] = [];
        for (const policy of body.policies) {
          const p = policy.body,
            old = await tx.chatConversation.findUnique({ where: { id: p.id } });
          if (old && (old.ownerId !== p.owner.body.id || old.visibility !== p.visibility))
            throw new ForbiddenException('Channel ownership cannot be replaced.');
          if (!active(policy, id) && p.owner.body.id !== id)
            throw new ForbiddenException('Only authorized members can carry channel state.');
          const policyHash = await hash(policy);
          const same = await tx.chatPolicy.findUnique({
            where: { conversationId_version: { conversationId: p.id, version: p.version } },
          });
          if (same && same.id !== policyHash)
            throw new ConflictException('Conflicting channel version.');
          if (old && (p.version < old.version || (old.deleted && !p.deleted))) continue;
          for (const member of p.members) await this.profile(tx, member.profile);
          await tx.chatConversation.upsert({
            where: { id: p.id },
            create: {
              id: p.id,
              ownerId: p.owner.body.id,
              visibility: p.visibility,
              version: p.version,
              policy: json(policy),
              deleted: p.deleted,
            },
            update: { version: p.version, policy: json(policy), deleted: p.deleted },
          });
          await tx.chatPolicy.upsert({
            where: { id: policyHash },
            create: {
              id: policyHash,
              conversationId: p.id,
              version: p.version,
              policy: json(policy),
            },
            update: {},
          });
          acceptedPolicies.push(policyHash);
          for (const member of p.members) {
            const participantId = member.profile.body.id;
            const leave = await tx.chatJoinRequest.findUnique({
              where: { conversationId_participantId: { conversationId: p.id, participantId } },
            });
            const removedAt = member.removedAt
              ? new Date(member.removedAt)
              : p.deleted || (leave?.action === 'LEAVE' && !leave.fulfilled)
                ? new Date(now)
                : null;
            await tx.chatMembership.upsert({
              where: { conversationId_participantId: { conversationId: p.id, participantId } },
              create: {
                conversationId: p.id,
                participantId,
                role: member.role,
                joinedAt: new Date(member.joinedAt),
                removedAt,
              },
              update: { role: member.role, removedAt },
            });
            if (
              leave &&
              ((leave.action === 'JOIN' && !removedAt) ||
                (leave.action === 'LEAVE' && (member.removedAt || p.deleted)))
            )
              await tx.chatJoinRequest.update({
                where: { id: leave.id },
                data: { fulfilled: true },
              });
          }
          await tx.chatMembership.updateMany({
            where: {
              conversationId: p.id,
              participantId: { notIn: p.members.map((m) => m.profile.body.id) },
              removedAt: null,
            },
            data: { removedAt: new Date(now) },
          });
        }
        for (const join of body.joins) {
          const j = join.body,
            c = await tx.chatConversation.findUnique({ where: { id: j.channelId } });
          if (!c || c.deleted) continue;
          if (j.participant.body.id !== id && c.ownerId !== id)
            throw new ForbiddenException('Membership request belongs to another person.');
          if (j.action === 'JOIN' && c.visibility !== 'OPEN')
            throw new ForbiddenException('This channel requires an owner-signed invitation.');
          const membership = await tx.chatMembership.findUnique({
            where: {
              conversationId_participantId: {
                conversationId: j.channelId,
                participantId: j.participant.body.id,
              },
            },
          });
          if (j.action === 'JOIN' && membership?.removedAt) continue;
          if (j.action === 'LEAVE' && c.ownerId === j.participant.body.id) continue;
          await this.profile(tx, j.participant);
          await tx.chatJoinRequest.upsert({
            where: {
              conversationId_participantId: {
                conversationId: j.channelId,
                participantId: j.participant.body.id,
              },
            },
            create: {
              id: j.id,
              conversationId: j.channelId,
              participantId: j.participant.body.id,
              profile: json(join),
              action: j.action,
            },
            update: { profile: json(join), action: j.action, fulfilled: false },
          });
          if (j.action === 'LEAVE')
            await tx.chatMembership.updateMany({
              where: { conversationId: j.channelId, participantId: j.participant.body.id },
              data: { removedAt: new Date(now) },
            });
        }
        await tx.chatBlock.deleteMany({ where: { participantId: id } });
        if (body.blocks.length)
          await tx.chatBlock.createMany({
            data: [...new Set(body.blocks)]
              .filter((b) => b !== id)
              .map((blockedId) => ({ participantId: id, blockedId })),
          });
        for (const message of body.messages) {
          const m = message.body,
            old = await tx.chatMessage.findUnique({ where: { id: m.id } }),
            messageHash = await hash(message);
          if (old) {
            const participant = await tx.chatMembership.findUnique({
              where: {
                conversationId_participantId: {
                  conversationId: old.conversationId,
                  participantId: id,
                },
              },
            });
            if (!participant || participant.removedAt)
              throw new ForbiddenException('Message is outside your conversation.');
            if (old.envelopeHash !== messageHash)
              throw new ConflictException('Message identifier was already used.');
            accepted.push(m.id);
            continue;
          }
          const conversation = await tx.chatConversation.findUnique({
              where: { id: m.conversationId },
            }),
            policy = conversation?.policy as unknown as ChannelPolicy | undefined;
          try {
            await validChatMessage(message, m.recipientId ? undefined : policy, now);
          } catch {
            rejected.push({ id: m.id, reason: 'MEMBERSHIP_OR_SIGNATURE' });
            continue;
          }
          if (m.recipientId) {
            if (id !== m.author.body.id && id !== m.recipientId) {
              rejected.push({ id: m.id, reason: 'NOT_PARTICIPANT' });
              continue;
            }
            if (!(await tx.chatParticipant.findUnique({ where: { id: m.recipientId } }))) {
              rejected.push({ id: m.id, reason: 'RECIPIENT_UNKNOWN' });
              continue;
            }
            if (
              await tx.chatBlock.count({
                where: {
                  OR: [
                    { participantId: m.recipientId, blockedId: m.author.body.id },
                    { participantId: m.author.body.id, blockedId: m.recipientId },
                  ],
                },
              })
            ) {
              rejected.push({ id: m.id, reason: 'BLOCKED' });
              continue;
            }
            await tx.chatConversation.upsert({
              where: { id: m.conversationId },
              create: { id: m.conversationId, visibility: 'DIRECT' },
              update: {},
            });
            await this.profile(tx, m.author);
            for (const participantId of [m.recipientId, m.author.body.id])
              await tx.chatMembership.upsert({
                where: {
                  conversationId_participantId: { conversationId: m.conversationId, participantId },
                },
                create: {
                  conversationId: m.conversationId,
                  participantId,
                  role: 'MEMBER',
                  joinedAt: new Date(now),
                },
                update: {},
              });
          } else {
            const membership = await tx.chatMembership.findUnique({
              where: {
                conversationId_participantId: {
                  conversationId: m.conversationId,
                  participantId: id,
                },
              },
            });
            const author = await tx.chatMembership.findUnique({
              where: {
                conversationId_participantId: {
                  conversationId: m.conversationId,
                  participantId: m.author.body.id,
                },
              },
            });
            if (
              !membership ||
              membership.removedAt ||
              !author ||
              author.removedAt ||
              conversation?.deleted
            ) {
              rejected.push({ id: m.id, reason: 'REMOVED' });
              continue;
            }
          }
          await this.profile(tx, m.author);
          await tx.chatMessage.create({
            data: {
              id: m.id,
              conversationId: m.conversationId,
              authorId: m.author.body.id,
              envelope: json(message),
              envelopeHash: messageHash,
              expiresAt: new Date(m.expiresAt),
            },
          });
          accepted.push(m.id);
        }
        for (const receipt of body.receipts) {
          const row = await tx.chatMessage.findUnique({
            where: { id: receipt.body.messageId },
            include: { conversation: true },
          });
          if (!row || receipt.body.recipient.body.id !== id) continue;
          const member = await tx.chatMembership.findUnique({
            where: {
              conversationId_participantId: {
                conversationId: row.conversationId,
                participantId: id,
              },
            },
          });
          if (!member || member.removedAt) continue;
          try {
            await validChatReceipt(
              receipt,
              row.envelope as unknown as ChatMessage,
              row.conversation.policy as unknown as ChannelPolicy,
              now,
            );
          } catch {
            continue;
          }
          await tx.chatDeliveryReceipt.upsert({
            where: {
              messageId_participantId_status: {
                messageId: row.id,
                participantId: id,
                status: receipt.body.status,
              },
            },
            create: {
              messageId: row.id,
              participantId: id,
              status: receipt.body.status,
              receipt: json(receipt),
            },
            update: {},
          });
          acceptedReceipts.push(row.id + ':' + id + ':' + receipt.body.status);
        }
        const wanted = [
          ...new Set([
            ...body.channelIds,
            ...body.policies.map((p) => p.body.id),
            ...body.joins.map((j) => j.body.channelId),
          ]),
        ];
        const memberships = await tx.chatMembership.findMany({
          where: {
            participantId: id,
            OR: [{ conversation: { visibility: 'DIRECT' } }, { conversationId: { in: wanted } }],
          },
          include: { conversation: true },
          take: 116,
          orderBy: { conversationId: 'asc' },
        });
        const allowed = memberships
          .filter((m) => !m.removedAt && !m.conversation.deleted)
          .map((m) => m.conversationId);
        const blocked = (
          await tx.chatBlock.findMany({ where: { OR: [{ participantId: id }, { blockedId: id }] } })
        ).map((b) => (b.participantId === id ? b.blockedId : b.participantId));
        const candidates = await tx.chatMessage.findMany({
          where: {
            id: { notIn: body.knownMessages },
            conversationId: { in: allowed },
            authorId: { notIn: blocked },
            expiresAt: { gt: new Date(now) },
          },
          include: { receipts: true },
          orderBy: [{ receivedAt: 'desc' }, { id: 'desc' }],
          take: 100,
        });
        const hashes = candidates
          .map((m) => (m.envelope as unknown as ChatMessage).body.policyHash)
          .filter((h): h is string => Boolean(h));
        const policies = await tx.chatPolicy.findMany({
          where: { id: { in: hashes }, conversationId: { in: allowed } },
          take: 200,
        });
        const included = new Set<string>(),
          messages: typeof candidates = [];
        let budget = 0;
        for (const candidate of candidates) {
          const message = candidate.envelope as unknown as ChatMessage,
            h = message.body.policyHash,
            p = policies.find((p) => p.id === h);
          if (
            h &&
            (!p ||
              ((p.policy as unknown as ChannelPolicy).body.visibility === 'INVITE' &&
                !active(p.policy as unknown as ChannelPolicy, id)))
          )
            continue;
          const size =
            bytes(candidate.envelope).length +
            (p && !included.has(p.id) ? bytes(p.policy).length : 0);
          if (budget + size > 512000) break;
          budget += size;
          messages.push(candidate);
          if (p) included.add(p.id);
        }
        for (const report of body.reports) {
          const held = await tx.chatMessage.findUnique({
            where: { id: report.messageId },
            include: {
              conversation: { include: { memberships: { where: { participantId: id } } } },
            },
          });
          if (!held?.conversation.memberships.length) continue;
          const prior = await tx.chatReport.findUnique({ where: { id: report.id } });
          if (prior && prior.participantId !== id) continue;
          await tx.chatReport.upsert({
            where: { id: report.id },
            create: { ...report, participantId: id },
            update: {},
          });
        }
        const joins = await tx.chatJoinRequest.findMany({
          where: { fulfilled: false, conversation: { ownerId: id, deleted: false } },
          take: 32,
          orderBy: { createdAt: 'asc' },
        });
        const confirmations = await tx.chatDeliveryReceipt.findMany({
          where: {
            messageId: { in: body.receiptMessageIds },
            message: {
              conversationId: { in: allowed },
              authorId: id,
              expiresAt: { gt: new Date(now) },
            },
          },
          take: 200,
        });
        const response = {
          v: 1,
          accepted,
          rejected,
          acceptedPolicies,
          acceptedReceipts,
          policies: memberships.map((m) => m.conversation.policy).filter(Boolean),
          historyPolicies: policies.filter((p) => included.has(p.id)).map((p) => p.policy),
          messages: messages.map((m) => m.envelope),
          receipts: confirmations.map((r) => r.receipt),
          joins: joins.map((j) => j.profile),
          removed: memberships
            .filter((m) => m.removedAt || m.conversation.deleted)
            .map((m) => m.conversationId),
        };
        await tx.chatSyncNonce.upsert({
          where: { id: body.id },
          create: {
            id: body.id,
            participantId: id,
            hash: requestHash,
            expiresAt: new Date(now + 600000),
            response: json(response),
          },
          update: {},
        });
        await tx.chatParticipant.update({
          where: { id },
          data: { lastSyncAt: new Date(body.issuedAt) },
        });
        await tx.chatSyncNonce.deleteMany({ where: { expiresAt: { lte: new Date(now) } } });
        await tx.chatMessage.deleteMany({ where: { expiresAt: { lte: new Date(now) } } });
        return response;
      },
      { timeout: 20000, maxWait: 15000 },
    );
  }
}
