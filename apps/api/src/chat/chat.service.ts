import { BadRequestException, ConflictException, ForbiddenException } from '@nestjs/common';
import { Prisma } from '@saathi/database';
import {
  bytes,
  MAX_CHAT_SYNC_BYTES,
  chatSyncSchema,
  hash,
  verify,
  validChatProfile,
  validChannelPolicy,
  validChatMessage,
  validChatReceipt,
  validChatJoin,
  channelCapabilities,
  validChatAction,
  type ChatAction,
  chatAttachmentRequestSchema,
  validChatAttachmentManifest,
  type ChatProfile,
  type ChannelPolicy,
  type ChatMessage,
} from '@saathi/protocol';
import { Database } from '../database';
type Tx = Prisma.TransactionClient;
const json = (value: unknown) => JSON.parse(JSON.stringify(value)) as Prisma.InputJsonValue;
const active = (policy: ChannelPolicy, id: string) => channelCapabilities(policy, id).canRead;
const membershipActions = ['APPROVE_JOIN', 'REJECT_JOIN', 'REMOVE', 'BAN', 'UNBAN', 'SET_ROLE'];

/** Operational plane only. Chat signatures confer no relief/account authority. */
export class ChatService {
  constructor(private readonly db: Database) {}
  private async pendingReports(tx: Tx, channelIds: string[]) {
    if (!channelIds.length) return [];
    const messages = await tx.chatMessage.findMany({
      where: { conversationId: { in: channelIds }, expiresAt: { gt: new Date() } },
      select: { id: true, conversationId: true },
      take: 500,
    });
    const reports = await tx.chatReport.findMany({
      where: { messageId: { in: messages.map((m) => m.id) } },
      orderBy: { createdAt: 'desc' },
      take: 100,
    });
    const reviews = await tx.chatAction.findMany({
      where: {
        conversationId: { in: channelIds },
        action: 'REVIEW_REPORT',
        targetId: { in: reports.map((r) => r.messageId) },
      },
      select: { targetId: true, receivedAt: true },
    });
    return reports
      .filter(
        (r) => !reviews.some((a) => a.targetId === r.messageId && a.receivedAt >= r.createdAt),
      )
      .map((r) => ({
        id: r.id,
        messageId: r.messageId,
        reason: r.reason,
        createdAt: r.createdAt.toISOString(),
        channelId: messages.find((m) => m.id === r.messageId)!.conversationId,
      }));
  }
  private async pendingKeyChange(tx: Tx, policy: ChannelPolicy) {
    return tx.chatAction.count({
      where: {
        conversationId: policy.body.id,
        version: policy.body.version,
        action: { in: ['REMOVE', 'BAN', 'SET_ROLE', 'APPROVE_JOIN'] },
        id: { notIn: policy.body.appliedActions ?? [] },
      },
    });
  }
  private async applyAction(tx: Tx, action: ChatAction, now: number) {
    const b = action.body,
      old = await tx.chatAction.findUnique({ where: { id: b.id } });
    const digest = await hash(action);
    if (old) {
      if (old.envelopeHash !== digest) throw new Error('Action identifier was reused.');
      return;
    }
    const channel = await tx.chatConversation.findUnique({ where: { id: b.channelId } });
    if (!channel?.policy || channel.deleted) throw new Error('Channel is unavailable.');
    const current = channel.policy as unknown as ChannelPolicy;
    if (
      !current.body.appliedActions?.includes(b.id) &&
      !['REACT', 'UNREACT', 'REVIEW_REPORT'].includes(b.action)
    ) {
      const family = membershipActions.includes(b.action)
        ? membershipActions
        : ['LOCK_THREAD', 'UNLOCK_THREAD'].includes(b.action)
          ? ['LOCK_THREAD', 'UNLOCK_THREAD']
          : ['HIDE_MESSAGE', 'RESTORE_MESSAGE'];
      if (
        await tx.chatAction.findFirst({
          where: {
            conversationId: b.channelId,
            version: current.body.version,
            targetId: b.targetId,
            action: { in: family },
            id: { notIn: current.body.appliedActions ?? [] },
          },
        })
      )
        throw new Error(
          'A conflicting action is awaiting a fresh owner policy. Refresh before retrying.',
        );
    }
    let policy = current;
    if (b.policyHash !== (await hash(current)) && current.body.appliedActions?.includes(b.id)) {
      const historic = await tx.chatPolicy.findUnique({ where: { id: b.policyHash } });
      if (!historic || historic.conversationId !== b.channelId)
        throw new Error('Action history is unavailable.');
      policy = historic.policy as unknown as ChannelPolicy;
      await validChatAction(action, policy, Date.parse(b.issuedAt));
    } else {
      await validChatAction(action, policy, now);
      const membership = await tx.chatMembership.findUnique({
        where: {
          conversationId_participantId: {
            conversationId: b.channelId,
            participantId: b.actor.body.id,
          },
        },
      });
      if (!membership || membership.removedAt) throw new Error('Moderator authority was removed.');
      const changed = await tx.chatAction.findFirst({
        where: {
          conversationId: b.channelId,
          targetId: b.actor.body.id,
          version: current.body.version,
          action: 'SET_ROLE',
        },
        orderBy: [{ receivedAt: 'desc' }, { id: 'desc' }],
      });
      if (changed) throw new Error('Moderator role is changing. Refresh permissions.');
    }
    if (membershipActions.includes(b.action)) {
      if (['APPROVE_JOIN', 'REJECT_JOIN'].includes(b.action)) {
        const join = await tx.chatJoinRequest.findUnique({
          where: {
            conversationId_participantId: {
              conversationId: b.channelId,
              participantId: b.targetId,
            },
          },
        });
        if (
          !current.body.appliedActions?.includes(b.id) &&
          (!join ||
            join.fulfilled ||
            join.action !== 'JOIN' ||
            current.body.bannedIds?.includes(b.targetId))
        )
          throw new Error('Pending request is unavailable.');
        if (b.action === 'REJECT_JOIN' && join)
          await tx.chatJoinRequest.update({
            where: { id: join.id },
            data: { fulfilled: true, decision: 'REJECTED' },
          });
        if (b.action === 'APPROVE_JOIN' && join)
          await tx.chatJoinRequest.update({
            where: { id: join.id },
            data: { decision: 'APPROVED' },
          });
      } else if (
        ['REMOVE', 'BAN', 'SET_ROLE'].includes(b.action) &&
        !policy.body.members.some((m) => m.profile.body.id === b.targetId)
      )
        throw new Error('Member is unavailable.');
    } else {
      const target = await tx.chatMessage.findUnique({ where: { id: b.targetId } });
      if (!target || target.conversationId !== b.channelId || target.expiresAt.getTime() <= now)
        throw new Error('Message is unavailable.');
      const m = target.envelope as unknown as ChatMessage;
      if (['LOCK_THREAD', 'UNLOCK_THREAD'].includes(b.action) && m.body.threadRootId)
        throw new Error('Choose a top-level thread.');
    }
    if ((await tx.chatAction.count({ where: { conversationId: b.channelId } })) >= 500)
      throw new Error('Moderation history is full.');
    await tx.chatAction.create({
      data: {
        id: b.id,
        conversationId: b.channelId,
        actorId: b.actor.body.id,
        targetId: b.targetId,
        action: b.action,
        version: b.version,
        envelope: json(action),
        envelopeHash: digest,
        expiresAt: new Date(now + 7 * 86400000),
      },
    });
    if (['REMOVE', 'BAN'].includes(b.action))
      await tx.chatMembership.updateMany({
        where: { conversationId: b.channelId, participantId: b.targetId },
        data: { removedAt: new Date(now) },
      });
  }
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
          if (b.chunks.length && (await this.pendingKeyChange(tx, policy)))
            throw new ForbiddenException('Private membership is changing. Wait for fresh keys.');
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
    if (
      bytes(request).length > MAX_CHAT_SYNC_BYTES ||
      Math.abs(Date.parse(body.issuedAt) - now) > 300000
    )
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
          ...(body.actions ?? []).map((a) => 'conv:' + a.body.channelId),
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
        const acceptedActions: string[] = [],
          rejectedActions: { id: string; reason: string }[] = [];
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
          if (old && p.version > old.version) {
            const pending = await tx.chatAction.findMany({
              where: {
                conversationId: p.id,
                version: old.version,
                action: { notIn: ['REACT', 'UNREACT', 'REVIEW_REPORT'] },
                id: { notIn: (old.policy as unknown as ChannelPolicy).body.appliedActions ?? [] },
              },
            });
            for (const row of pending) {
              const a = (row.envelope as unknown as ChatAction).body;
              const member = p.members.find((m) => m.profile.body.id === a.targetId);
              if (
                !p.appliedActions?.includes(a.id) ||
                (['REMOVE', 'BAN', 'REJECT_JOIN'].includes(a.action) &&
                  member &&
                  !member.removedAt) ||
                (a.action === 'BAN' && !p.bannedIds?.includes(a.targetId)) ||
                (a.action === 'UNBAN' && p.bannedIds?.includes(a.targetId)) ||
                (a.action === 'APPROVE_JOIN' && (!member || member.removedAt)) ||
                (a.action === 'SET_ROLE' && member?.role !== a.role) ||
                (a.action === 'LOCK_THREAD' && !p.moderation?.lockedThreads.includes(a.targetId)) ||
                (a.action === 'UNLOCK_THREAD' &&
                  p.moderation?.lockedThreads.includes(a.targetId)) ||
                (a.action === 'HIDE_MESSAGE' &&
                  !p.moderation?.hiddenMessages.includes(a.targetId)) ||
                (a.action === 'RESTORE_MESSAGE' &&
                  p.moderation?.hiddenMessages.includes(a.targetId))
              )
                throw new ConflictException(
                  'Refresh and incorporate accepted moderation before changing channel state.',
                );
            }
          }
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
          const policy = c.policy as unknown as ChannelPolicy;
          const membership = await tx.chatMembership.findUnique({
            where: {
              conversationId_participantId: {
                conversationId: j.channelId,
                participantId: j.participant.body.id,
              },
            },
          });
          const priorJoin = await tx.chatJoinRequest.findUnique({
            where: {
              conversationId_participantId: {
                conversationId: j.channelId,
                participantId: j.participant.body.id,
              },
            },
          });
          if (j.participant.body.id !== id && !channelCapabilities(policy, id).canManageMembers)
            throw new ForbiddenException('Membership request belongs to another person.');
          if (j.action === 'JOIN') {
            if (policy.body.bannedIds?.includes(j.participant.body.id))
              throw new ForbiddenException('This identity is banned from the channel.');
            if (membership && !membership.removedAt) continue;
            if (priorJoin && (await hash(priorJoin.profile)) === (await hash(join))) continue;
            if (c.visibility !== 'OPEN') {
              const invite = j.invitation?.body;
              if (
                !invite ||
                invite.policyHash !== (await hash(policy)) ||
                invite.owner.body.id !== c.ownerId ||
                !channelCapabilities(policy, (invite.issuer ?? invite.owner).body.id).canInvite ||
                invite.admission !== policy.body.settings?.admission
              )
                throw new ForbiddenException(
                  'This channel requires a current authenticated invitation.',
                );
            }
          }
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
            update: {
              profile: json(join),
              action: j.action,
              fulfilled: false,
              decision: 'PENDING',
            },
          });
          if (j.action === 'LEAVE')
            await tx.chatMembership.updateMany({
              where: { conversationId: j.channelId, participantId: j.participant.body.id },
              data: { removedAt: new Date(now) },
            });
        }
        for (const action of body.actions ?? []) {
          try {
            await this.applyAction(tx, action, now);
            acceptedActions.push(action.body.id);
          } catch (error) {
            rejectedActions.push({
              id: action.body.id,
              reason: error instanceof Error ? error.message : 'Action rejected.',
            });
          }
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
            if (!policy || (await this.pendingKeyChange(tx, policy))) {
              rejected.push({ id: m.id, reason: 'WAITING_FOR_FRESH_MEMBERSHIP' });
              continue;
            }
            if (m.threadRootId) {
              if (
                !channelCapabilities(policy, m.author.body.id).canCreateThreads &&
                !(await tx.chatMessage.findFirst({
                  where: {
                    conversationId: m.conversationId,
                    envelope: { path: ['body', 'threadRootId'], equals: m.threadRootId },
                  },
                }))
              ) {
                rejected.push({ id: m.id, reason: 'THREAD_CREATION_NOT_PERMITTED' });
                continue;
              }
              const root = await tx.chatMessage.findUnique({ where: { id: m.threadRootId } });
              const rootMessage = root?.envelope as unknown as ChatMessage | undefined;
              const latest = await tx.chatAction.findFirst({
                where: {
                  conversationId: m.conversationId,
                  targetId: m.threadRootId,
                  action: { in: ['LOCK_THREAD', 'UNLOCK_THREAD'] },
                },
                orderBy: [{ receivedAt: 'desc' }, { id: 'desc' }],
              });
              if (
                !root ||
                root.conversationId !== m.conversationId ||
                root.expiresAt.getTime() <= now ||
                rootMessage?.body.threadRootId ||
                (latest
                  ? latest.action === 'LOCK_THREAD'
                  : policy.body.moderation?.lockedThreads.includes(m.threadRootId))
              ) {
                rejected.push({ id: m.id, reason: 'THREAD_UNAVAILABLE_OR_LOCKED' });
                continue;
              }
            }
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
            (p && !included.has(p.id) && !body.knownPolicyHashes?.includes(p.id)
              ? bytes(p.policy).length
              : 0);
          if (budget + size > 512000) break;
          budget += size;
          messages.push(candidate);
          if (p) included.add(p.id);
        }
        for (const report of body.reports) {
          if (report.personId) {
            const known = await tx.chatParticipant.findUnique({ where: { id: report.personId } });
            if (!known || report.personId === id) continue;
            await tx.moderationReport.upsert({
              where: { id: report.id },
              create: {
                id: report.id,
                entityType: 'CHAT_USER',
                entityId: report.personId,
                reason: report.reason,
              },
              update: {},
            });
            continue;
          }
          const held = await tx.chatMessage.findUnique({
            where: { id: report.messageId! },
            include: {
              conversation: { include: { memberships: { where: { participantId: id } } } },
            },
          });
          if (!held?.conversation.memberships.length) continue;
          const prior = await tx.chatReport.findUnique({ where: { id: report.id } });
          if (prior && prior.participantId !== id) continue;
          await tx.chatReport.upsert({
            where: { id: report.id },
            create: {
              id: report.id,
              messageId: report.messageId!,
              reason: report.reason,
              participantId: id,
            },
            update: {},
          });
        }
        const joins = await tx.chatJoinRequest.findMany({
          where: {
            id: { notIn: body.knownJoinIds ?? [] },
            fulfilled: false,
            conversationId: {
              in: memberships
                .filter(
                  (m) =>
                    m.conversation.policy &&
                    channelCapabilities(m.conversation.policy as unknown as ChannelPolicy, id)
                      .canManageMembers,
                )
                .map((m) => m.conversationId),
            },
          },
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
          capabilities: { policyPaging: true, channelMembers: 200 },
          accepted,
          rejected,
          acceptedPolicies,
          acceptedReceipts,
          acceptedActions,
          rejectedActions,
          acceptedReports: (
            await tx.chatReport.findMany({
              where: { id: { in: body.reports.map((r) => r.id) }, participantId: id },
            })
          )
            .map((r) => r.id)
            .concat(
              (
                await tx.moderationReport.findMany({
                  where: {
                    id: { in: body.reports.filter((r) => r.personId).map((r) => r.id) },
                    entityType: 'CHAT_USER',
                  },
                })
              ).map((r) => r.id),
            ),
          reports: await this.pendingReports(
            tx,
            memberships
              .filter(
                (m) =>
                  m.conversation.policy &&
                  channelCapabilities(m.conversation.policy as unknown as ChannelPolicy, id)
                    .canModerate,
              )
              .map((m) => m.conversationId),
          ),
          actions: (
            await tx.chatAction.findMany({
              where: { conversationId: { in: allowed } },
              orderBy: [{ receivedAt: 'desc' }, { id: 'desc' }],
              take: 100,
            })
          )
            .reverse()
            .map((a) => a.envelope),
          policies: [] as unknown[],
          historyPolicies: policies
            .filter((p) => included.has(p.id) && !body.knownPolicyHashes?.includes(p.id))
            .map((p) => p.policy),
          messages: messages.map((m) => m.envelope),
          receipts: confirmations.map((r) => r.receipt),
          joins: joins.map((j) => j.profile),
          joinStates: (
            await tx.chatJoinRequest.findMany({
              where: { participantId: id, conversationId: { in: wanted } },
              take: 16,
            })
          ).map((j) => ({ channelId: j.conversationId, status: j.decision })),
          removed: memberships
            .filter((m) => m.removedAt || m.conversation.deleted)
            .map((m) => m.conversationId),
        };
        // Page changed rosters first. Clients acknowledge their hashes on the next sync;
        // unchanged 200-person rosters are never copied through every poll.
        const currentPolicies = memberships.map((m) => m.conversation.policy).filter(Boolean);
        let rosterBudget = 0;
        for (const policy of currentPolicies) {
          const digest = await hash(policy);
          if (body.knownPolicyHashes?.includes(digest)) continue;
          const size = bytes(policy).length;
          if (rosterBudget + size > 512000) continue;
          rosterBudget += size;
          response.policies.push(policy);
        }
        // Admission invitations can also carry a roster. Keep the whole reply bounded,
        // and leave omitted joins pending for the next acknowledged page.
        while (bytes(response).length > 1500000 && response.joins.length) response.joins.pop();
        if (bytes(response).length > 1500000)
          throw new BadRequestException('Chat response exceeds its synchronization budget.');
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
