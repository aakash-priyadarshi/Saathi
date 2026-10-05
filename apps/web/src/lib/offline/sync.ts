import {
  createEnvelope,
  exportPublic,
  generateKeys,
  envelopeSchema,
  receiptSchema,
  validEnvelope,
  validReceipt,
  type EventInput,
  type PublicKey,
  type Receipt,
} from '@saathi/protocol';
import { api, write, ApiError } from '../api';
import { setting, setSetting, events, saveEvent, type Preparation, type SavedEvent } from './store';
let syncing = false;
export async function pinReceiptKey() {
  const key = await api<{ publicKey: PublicKey }>('/sync/receipt-key');
  await setSetting('receipt-key', key.publicKey);
  return key.publicKey;
}
export async function preparePublishing() {
  const preparation = await api<Preparation>('/sync/preparation');
  const old = await setting<Preparation>('preparation');
  if (old && old.user.id !== preparation.user.id)
    throw new Error(
      'Clear or export the previous person’s saved work before preparing this phone.',
    );
  let keys = await setting<CryptoKeyPair>('signing-keys');
  if (!keys) {
    keys = await generateKeys();
    await setSetting('signing-keys', keys);
  }
  let device = await setting<{ id: string }>('signing-device');
  if (device) {
    const devices = await api<{ id: string; revokedAt: string | null }[]>('/sync/devices');
    if (!devices.some((d) => d.id === device!.id && !d.revokedAt)) {
      device = undefined;
      keys = await generateKeys();
      await setSetting('signing-keys', keys);
    }
  }
  if (!device) {
    device = await write<{ id: string }>('/sync/devices', await exportPublic(keys.publicKey));
    await setSetting('signing-device', device);
  }
  await pinReceiptKey();
  await setSetting('preparation', preparation);
  return preparation;
}
export async function authorEvent(
  type: EventInput['type'],
  payload: EventInput['payload'],
  organizationId: string,
) {
  const preparation = await setting<Preparation>('preparation'),
    keys = await setting<CryptoKeyPair>('signing-keys'),
    device = await setting<{ id: string }>('signing-device');
  if (!preparation || !keys || !device)
    throw new Error(
      'Prepare offline publishing while signed in and connected first. Your draft can still be saved.',
    );
  const envelope = await createEnvelope(
    {
      type,
      payload,
      organizationId,
      authorId: preparation.user.id,
      deviceId: device.id,
    } as EventInput,
    keys,
  );
  const event: SavedEvent = {
    id: envelope.body.id,
    envelope,
    hops: 0,
    own: true,
    savedAt: new Date().toISOString(),
  };
  await saveEvent(event);
  return event;
}
export async function receiveEvent(value: unknown, hops: number) {
  const envelope = envelopeSchema.parse(value);
  if (
    !Number.isInteger(hops) ||
    hops < 1 ||
    hops > envelope.body.maxHops ||
    Date.parse(envelope.body.expiresAt) <= Date.now() ||
    !(await validEnvelope(envelope))
  )
    throw new Error('This nearby update is expired or could not be checked.');
  const previous = (await events()).find((e) => e.id === envelope.body.id);
  if (previous) {
    if (previous.envelope.signature !== envelope.signature)
      throw new Error('A nearby update has conflicting contents.');
    return previous;
  }
  const event: SavedEvent = {
    id: envelope.body.id,
    envelope,
    hops,
    own: false,
    savedAt: new Date().toISOString(),
  };
  await saveEvent(event);
  return event;
}
export async function acceptReceipt(value: unknown) {
  const receipt = receiptSchema.parse(value),
    key = await setting<PublicKey>('receipt-key');
  const record = (await events()).find((e) => e.id === receipt.body.eventId);
  if (!record || !key || !(await validReceipt(receipt, key, record.envelope)))
    throw new Error('Reconnect to Saathi before trusting this publication confirmation.');
  // Do not let an older acceptance overwrite a later withdrawal.
  if (record.receipt && record.receipt.body.recordedAt > receipt.body.recordedAt) return;
  await saveEvent({ ...record, receipt, error: undefined });
}
export async function syncEvents() {
  if (syncing) return;
  syncing = true;
  try {
    const key = await pinReceiptKey();
    let carrier = await setting<string>('carrier-id');
    if (!carrier) {
      carrier = crypto.randomUUID();
      await setSetting('carrier-id', carrier);
    }
    const records = await events();
    for (const record of records
      .filter((e) => !e.receipt)
      .sort(
        (a, b) =>
          Number(
            b.envelope.body.type === 'REQUEST_CREATED' &&
              'priority' in b.envelope.body.payload &&
              b.envelope.body.payload.priority === 'URGENT',
          ) -
          Number(
            a.envelope.body.type === 'REQUEST_CREATED' &&
              'priority' in a.envelope.body.payload &&
              a.envelope.body.payload.priority === 'URGENT',
          ),
      )) {
      try {
        const receipt = await write<Receipt>('/sync/events', {
          envelope: record.envelope,
          carrierId: carrier,
        });
        if (!(await validReceipt(receipt, key, record.envelope)))
          throw new Error('Saathi’s confirmation could not be checked.');
        await saveEvent({ ...record, receipt, error: undefined });
      } catch (error) {
        await saveEvent({
          ...record,
          error: error instanceof Error ? error.message : 'Waiting for Saathi.',
        });
        // Stop a batch when the path fails; do not spend a timeout per saved event.
        if (!(error instanceof ApiError) || error.status >= 500 || error.status === 429) return;
      }
    }
    for (let offset = 0; offset < records.length; offset += 50) {
      const receipts = await write<Receipt[]>('/sync/receipts', {
        ids: records.slice(offset, offset + 50).map((e) => e.id),
      });
      for (const receipt of receipts) await acceptReceipt(receipt);
    }
  } finally {
    syncing = false;
  }
}
