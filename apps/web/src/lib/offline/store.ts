import { openDB, type DBSchema } from 'idb';
import type { Envelope, Receipt } from '@saathi/protocol';
export type Preparation = {
  user: { id: string; displayName: string };
  organizations: { id: string; name: string }[];
  points: { id: string; name: string; publicLocation: string; organizationId: string }[];
  preparedAt: string;
};
export type SavedEvent = {
  id: string;
  envelope: Envelope;
  hops: number;
  own: boolean;
  sharedAt?: string;
  receipt?: Receipt;
  error?: string;
  savedAt: string;
};
export type Message = {
  id: string;
  peer: string;
  text: string;
  direction: 'IN' | 'OUT';
  createdAt: string;
  deliveredAt?: string;
};
export type Attachment = {
  id: string;
  name: string;
  mime: string;
  size: number;
  hash: string;
  chunks: Record<string, Uint8Array>;
  complete: boolean;
  direction: 'IN' | 'OUT';
  savedAt: string;
};
interface SaathiDB extends DBSchema {
  settings: { key: string; value: unknown };
  snapshots: { key: string; value: { path: string; data: unknown; savedAt: string } };
  events: { key: string; value: SavedEvent };
  messages: { key: string; value: Message };
  attachments: { key: string; value: Attachment };
  drafts: {
    key: string;
    value: {
      id: string;
      kind: string;
      values: Record<string, string>;
      media?: File[];
      savedAt: string;
    };
  };
}
export const changed = () => {
  if (typeof window !== 'undefined') window.dispatchEvent(new Event('saathi-local-change'));
};
export const db = () =>
  openDB<SaathiDB>('saathi-offline-v1', 1, {
    upgrade(db) {
      for (const name of [
        'settings',
        'snapshots',
        'events',
        'messages',
        'attachments',
        'drafts',
      ] as const)
        db.createObjectStore(name);
    },
  });
export async function setting<T>(key: string): Promise<T | undefined> {
  return (await db()).get('settings', key) as Promise<T | undefined>;
}
export async function setSetting(key: string, value: unknown) {
  await (await db()).put('settings', value, key);
  changed();
}
export async function saveSnapshot(path: string, data: unknown) {
  const database = await db();
  await database.put('snapshots', { path, data, savedAt: new Date().toISOString() }, path);
  const keys = await database.getAllKeys('snapshots');
  if (keys.length > 120)
    await database.delete(
      'snapshots',
      keys.find((k) => k.startsWith('/public/requests/')) ?? keys[0]!,
    );
}
export async function snapshot<T>(path: string) {
  return (await db()).get('snapshots', path) as Promise<
    { path: string; data: T; savedAt: string } | undefined
  >;
}
export async function saveEvent(event: SavedEvent) {
  const database = await db();
  if (!(await database.get('events', event.id)) && (await database.count('events')) >= 500)
    throw new Error(
      'This phone is full of saved updates. Export or clear old work before receiving more.',
    );
  await database.put('events', event, event.id);
  changed();
}
export async function events() {
  return (await db()).getAll('events');
}
export async function message(value: Message) {
  const database = await db();
  if ((await database.count('messages')) >= 1000 && !(await database.get('messages', value.id)))
    throw new Error('Message storage is full. Export or clear old messages.');
  await database.put('messages', value, value.id);
  changed();
}
export async function messages() {
  return (await db()).getAll('messages');
}
export async function exportWork() {
  const database = await db();
  return {
    format: 'saathi-local-backup-v1',
    exportedAt: new Date().toISOString(),
    events: await database.getAll('events'),
    messages: await database.getAll('messages'),
    drafts: (await database.getAll('drafts')).map(({ media, ...draft }) => ({
      ...draft,
      mediaNames: media?.map((f) => f.name),
    })),
  };
}
export async function clearPrivate() {
  const database = await db();
  const tx = database.transaction(
    ['settings', 'events', 'messages', 'attachments', 'drafts'],
    'readwrite',
  );
  for (const name of ['events', 'messages', 'attachments', 'drafts'] as const)
    await tx.objectStore(name).clear();
  for (const key of ['preparation', 'signing-keys', 'signing-device'])
    await tx.objectStore('settings').delete(key);
  await tx.done;
  changed();
}
