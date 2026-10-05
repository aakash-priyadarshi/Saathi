import { z } from 'zod';
import { base64, unbase64 } from '@saathi/protocol';
import { LocalPeer, type Frame } from './peer';
import {
  db,
  message,
  messages,
  events,
  saveEvent,
  changed,
  type Attachment,
} from '../offline/store';
import { receiveEvent, acceptReceipt } from '../offline/sync';
const small = z
  .object({ text: z.string().min(1).max(4000), createdAt: z.string().datetime() })
  .strict();
const fileOffer = z
  .object({
    id: z.string().uuid(),
    name: z.string().max(100),
    mime: z.enum(['image/jpeg', 'image/png', 'image/webp', 'text/plain']),
    size: z.number().int().positive().max(1048576),
    hash: z.string().regex(/^[a-f0-9]{64}$/),
  })
  .strict();
export class NearbySession {
  readonly peer = new LocalPeer();
  confirmed = false;
  private fragments = new Map<string, { parts: string[]; total: number; hops: number }>();
  private transferCancelled = new Set<string>();
  private sharing = new Set<string>();
  private requested = new Set<string>();
  private offered = new Set<string>();
  private accepted = new Set<string>();
  reset() {
    this.remoteMedia = false;
    this.remoteFiles = false;
    this.sharing.clear();
    this.requested.clear();
    this.offered.clear();
    this.accepted.clear();
    this.fragments.clear();
    this.onChange();
  }
  onChange: () => void = () => {};
  onError: (message: string) => void = () => {};
  onCall: (video: boolean) => void = () => {};
  onAccepted: () => void = () => {};
  onEnded: () => void = () => {};
  onFile: (offer: z.infer<typeof fileOffer>) => void = () => {};
  remoteMedia = false;
  remoteFiles = false;
  private incoming = Promise.resolve();
  constructor() {
    this.peer.onFrame = (frame) => {
      this.incoming = this.incoming
        .then(() => this.receive(frame))
        .catch((error) =>
          this.onError(
            error instanceof Error ? error.message : 'The nearby update could not be saved.',
          ),
        );
    };
  }
  private async receive(frame: Frame) {
    if (frame.kind === 'HELLO') {
      const capabilities = z
        .object({
          protocol: z.literal(1),
          maxFrame: z.number().int().min(16000),
          media: z.boolean(),
          files: z.boolean(),
        })
        .strict()
        .parse(frame.value);
      this.remoteMedia = capabilities.media;
      this.remoteFiles = capabilities.files;
      this.onChange();
      return;
    }
    if (frame.kind === 'NATIVE_CAPS') {
      z.object({ largeFiles: z.boolean() }).strict().parse(frame.value);
      return; // Browsers retain their 1 MiB policy regardless of a native peer's larger limit.
    }
    if (!this.confirmed) return;
    if (frame.kind === 'MESSAGE') {
      const value = small.parse(frame.value);
      const previous = (await db()).get('messages', frame.id);
      const saved = await previous;
      if (
        saved &&
        (saved.direction !== 'IN' ||
          saved.text !== value.text ||
          saved.createdAt !== value.createdAt)
      )
        throw new Error('This message ID contains conflicting information.');
      await message({
        id: frame.id,
        peer: this.peer.session,
        text: value.text,
        createdAt: value.createdAt,
        direction: 'IN',
        deliveredAt: new Date().toISOString(),
      });
      await this.peer.send('ACK', { id: frame.id });
    } else if (frame.kind === 'ACK') {
      const id = z.object({ id: z.string().uuid() }).strict().parse(frame.value).id;
      const record = (await messages()).find(
        (m) => m.id === id && m.direction === 'OUT' && m.peer === this.peer.session,
      );
      if (record) await message({ ...record, deliveredAt: new Date().toISOString() });
      const event = (await events()).find((e) => e.id === id);
      if (event) await saveEvent({ ...event, sharedAt: new Date().toISOString() });
    } else if (frame.kind === 'INVENTORY') {
      const ids = z.array(z.string().uuid()).max(500).parse(frame.value);
      const saved = new Set((await events()).map((e) => e.id));
      for (let offset = 0; offset < ids.length; offset += 50)
        await this.peer.send(
          'NEED',
          ids.slice(offset, offset + 50).map((id) => ({ id, hasEvent: saved.has(id) })),
        );
    } else if (frame.kind === 'NEED') {
      const wanted = z
        .array(z.object({ id: z.string().uuid(), hasEvent: z.boolean() }).strict())
        .max(50)
        .parse(frame.value);
      const saved = await events();
      for (const item of wanted) {
        if (!this.sharing.has(item.id) || this.requested.has(item.id)) continue;
        this.requested.add(item.id);
        const event = saved.find((e) => e.id === item.id);
        if (!event) continue;
        if (
          !item.hasEvent &&
          event.hops < event.envelope.body.maxHops &&
          Date.parse(event.envelope.body.expiresAt) > Date.now()
        ) {
          const raw = new TextEncoder().encode(JSON.stringify(event.envelope)),
            total = Math.ceil(raw.length / 12288);
          for (let part = 0; part < total; part++)
            await this.peer.send('EVENT', {
              id: event.id,
              part,
              total,
              hops: event.hops + 1,
              data: base64(raw.subarray(part * 12288, (part + 1) * 12288)),
            });
        }
        if (event.receipt) await this.peer.send('RECEIPT', event.receipt);
      }
    } else if (frame.kind === 'EVENT') {
      const value = z
        .object({
          id: z.string().uuid(),
          part: z.number().int().min(0).max(7),
          total: z.number().int().min(1).max(8),
          data: z.string().max(16400),
          hops: z.number().int().min(1).max(8),
        })
        .strict()
        .parse(frame.value);
      if (value.part >= value.total) throw new Error('This nearby update is incomplete.');
      if (!this.fragments.has(value.id) && this.fragments.size >= 8)
        throw new Error('Too many incomplete updates. Reconnect to try again.');
      const pending = this.fragments.get(value.id) ?? {
        parts: Array<string>(value.total).fill(''),
        total: value.total,
        hops: value.hops,
      };
      if (pending.total !== value.total || pending.hops !== value.hops)
        throw new Error('This nearby update has inconsistent parts.');
      pending.parts[value.part] = value.data;
      this.fragments.set(value.id, pending);
      if (pending.parts.every(Boolean)) {
        this.fragments.delete(value.id);
        const combined = pending.parts.map(unbase64),
          size = combined.reduce((n, p) => n + p.length, 0);
        if (size > 65536) throw new Error('This nearby update is too large.');
        const raw = new Uint8Array(size);
        let offset = 0;
        for (const part of combined) {
          raw.set(part, offset);
          offset += part.length;
        }
        const received = await receiveEvent(JSON.parse(new TextDecoder().decode(raw)), value.hops);
        if (received.id !== value.id) throw new Error('This update ID is inconsistent.');
        await this.peer.send('ACK', { id: received.id });
      }
    } else if (frame.kind === 'RECEIPT') {
      const id = z.object({ body: z.object({ eventId: z.string().uuid() }) }).parse(frame.value)
        .body.eventId;
      if ((await events()).some((e) => e.id === id)) await acceptReceipt(frame.value);
    } else if (frame.kind === 'CALL')
      this.onCall(z.object({ video: z.boolean() }).strict().parse(frame.value).video);
    else if (frame.kind === 'CALL_ACCEPT') this.onAccepted();
    else if (frame.kind === 'CALL_END') {
      this.peer.stopMedia();
      this.onEnded();
    } else if (frame.kind === 'FILE_OFFER') this.onFile(fileOffer.parse(frame.value));
    else if (frame.kind === 'FILE_ACCEPT') {
      const { id, missing } = z
        .object({
          id: z.string().uuid(),
          missing: z.array(z.number().int().min(0).max(127)).max(128),
        })
        .strict()
        .parse(frame.value);
      const file = await (await db()).get('attachments', id);
      if (!file || file.direction !== 'OUT' || !this.offered.has(id)) return;
      void this.transfer(file, missing).catch((error) => this.onError(String(error)));
    } else if (frame.kind === 'FILE_CHUNK') {
      const { id, index, data } = z
        .object({
          id: z.string().uuid(),
          index: z.number().int().min(0).max(127),
          data: z.string().max(11000),
        })
        .strict()
        .parse(frame.value);
      const database = await db(),
        file = await database.get('attachments', id);
      if (
        !file ||
        file.direction !== 'IN' ||
        !this.accepted.has(id) ||
        this.transferCancelled.has(id) ||
        index >= Math.ceil(file.size / 8192)
      )
        return;
      const chunk = unbase64(data),
        expected = Math.min(8192, file.size - index * 8192);
      if (chunk.length !== expected) throw new Error('This attachment has an invalid part.');
      file.chunks[index] = chunk;
      await database.put('attachments', file, id);
      changed();
    } else if (frame.kind === 'FILE_DONE') {
      const { id } = z.object({ id: z.string().uuid() }).strict().parse(frame.value);
      const database = await db(),
        file = await database.get('attachments', id);
      if (!file || file.direction !== 'IN' || !this.accepted.has(id)) return;
      const raw = attachmentBytes(file);
      if (raw.length === file.size && (await byteHash(raw)) === file.hash) {
        file.complete = true;
        await database.put('attachments', file, id);
        await this.peer.send('ACK', { id });
        changed();
      } else
        throw new Error('The attachment is incomplete. Accept it again to resume missing parts.');
    } else if (frame.kind === 'FILE_CANCEL') {
      const { id } = z.object({ id: z.string().uuid() }).strict().parse(frame.value);
      this.transferCancelled.add(id);
    }
    this.onChange();
  }
  private async transfer(file: Attachment, missing: number[]) {
    for (const index of missing) {
      if (this.transferCancelled.has(file.id)) break;
      if (this.peer.videoPaused)
        throw new Error(
          'The nearby connection is weaker. Attachment parts stay saved; resume when the connection improves.',
        );
      const data = file.chunks[index];
      if (data) await this.peer.send('FILE_CHUNK', { id: file.id, index, data: base64(data) });
      // Give constrained receivers time to persist encrypted parts, and yield
      // the connection to messages/events instead of flooding their bounded queue.
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    if (!this.transferCancelled.has(file.id)) await this.peer.send('FILE_DONE', { id: file.id });
  }
  async sendMessage(text: string) {
    small.parse({ text, createdAt: new Date().toISOString() });
    const value = {
      id: crypto.randomUUID(),
      peer: this.peer.session,
      text,
      direction: 'OUT' as const,
      createdAt: new Date().toISOString(),
    };
    await message(value);
    if (this.peer.state === 'CONNECTED' && this.confirmed)
      await this.peer.send('MESSAGE', { text, createdAt: value.createdAt }, value.id);
  }
  async shareWork() {
    if (!this.confirmed) throw new Error('Compare the pairing codes before sharing.');
    const eligible = (await events())
      .filter(
        (e) =>
          e.receipt ||
          (e.hops < e.envelope.body.maxHops && Date.parse(e.envelope.body.expiresAt) > Date.now()),
      )
      .sort(
        (a, b) =>
          Number(
            b.envelope.body.type === 'REQUEST_CREATED' &&
              b.envelope.body.payload.priority === 'URGENT',
          ) -
          Number(
            a.envelope.body.type === 'REQUEST_CREATED' &&
              a.envelope.body.payload.priority === 'URGENT',
          ),
      );
    this.sharing = new Set(eligible.map((e) => e.id));
    this.requested.clear();
    await this.peer.send('INVENTORY', [...this.sharing]);
  }
  async resendMessages() {
    for (const value of (await messages()).filter((m) => m.direction === 'OUT' && !m.deliveredAt)) {
      await message({ ...value, peer: this.peer.session });
      await this.peer.send('MESSAGE', { text: value.text, createdAt: value.createdAt }, value.id);
    }
  }
  async offerFile(file: File) {
    if (this.peer.videoPaused)
      throw new Error(
        'The connection is weaker. Send relief text first and try the attachment later.',
      );
    if (!this.confirmed || !this.remoteFiles)
      throw new Error('Compare the pairing codes and connect before sharing attachments.');
    const raw = new Uint8Array(await file.arrayBuffer()),
      id = crypto.randomUUID(),
      digest = await byteHash(raw);
    const offer = fileOffer.parse({
      id,
      name: file.name.slice(0, 100),
      mime: file.type || 'text/plain',
      size: file.size,
      hash: digest,
    });
    const database = await db();
    if ((await database.count('attachments')) >= 20)
      throw new Error('Attachment storage is full. Clear old attachments first.');
    const chunks: Record<string, Uint8Array> = {};
    for (let offset = 0; offset < raw.length; offset += 8192)
      chunks[offset / 8192] = raw.slice(offset, offset + 8192);
    await database.put(
      'attachments',
      { ...offer, chunks, complete: true, direction: 'OUT', savedAt: new Date().toISOString() },
      id,
    );
    this.offered.add(id);
    await this.peer.send('FILE_OFFER', offer);
    changed();
  }
  async reofferFile(file: Attachment) {
    if (!this.confirmed || !this.remoteFiles || file.direction !== 'OUT')
      throw new Error('Pair and compare codes before sharing this file again.');
    this.offered.add(file.id);
    this.transferCancelled.delete(file.id);
    await this.peer.send(
      'FILE_OFFER',
      fileOffer.parse({
        id: file.id,
        name: file.name,
        mime: file.mime,
        size: file.size,
        hash: file.hash,
      }),
    );
  }
  async acceptFile(offer: z.infer<typeof fileOffer>) {
    const database = await db();
    let file = await database.get('attachments', offer.id);
    if (file && (file.hash !== offer.hash || file.size !== offer.size || file.direction !== 'IN'))
      throw new Error('This attachment has conflicting contents.');
    if (!file) {
      if ((await database.count('attachments')) >= 20)
        throw new Error('Attachment storage is full.');
      file = {
        ...offer,
        chunks: {},
        complete: false,
        direction: 'IN',
        savedAt: new Date().toISOString(),
      };
      await database.put('attachments', file, file.id);
    }
    this.transferCancelled.delete(offer.id);
    this.accepted.add(offer.id);
    const missing = Array.from({ length: Math.ceil(offer.size / 8192) }, (_, i) => i).filter(
      (i) => !file.chunks[i],
    );
    await this.peer.send('FILE_ACCEPT', { id: offer.id, missing });
  }
  async cancelFile(id: string) {
    this.transferCancelled.add(id);
    await this.peer.send('FILE_CANCEL', { id });
  }
}
export function attachmentBytes(file: Attachment) {
  const raw = new Uint8Array(file.size);
  for (let i = 0; i < Math.ceil(file.size / 8192); i++) {
    const chunk = file.chunks[i];
    if (!chunk) return new Uint8Array();
    raw.set(chunk, i * 8192);
  }
  return raw;
}
export async function byteHash(raw: Uint8Array) {
  return Array.from(
    new Uint8Array(await crypto.subtle.digest('SHA-256', raw as Uint8Array<ArrayBuffer>)),
    (x) => x.toString(16).padStart(2, '0'),
  ).join('');
}
