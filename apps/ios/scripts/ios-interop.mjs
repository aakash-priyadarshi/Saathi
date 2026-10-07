// Validates iPhone-made chat documents with the shared TypeScript protocol (the server's and web client's rules).
// Usage: SWARM_INTEROP_OUT=/tmp/x.json swift test --filter InteropExportTests && node apps/ios/scripts/ios-interop.mjs /tmp/x.json
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../../packages/protocol/package.json', import.meta.url));
const p = require('./dist/index.js');
const d = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const check = async (name, f) => { await f(); console.log('ok', name); };
await check('profile', () => p.validChatProfile(d.profile));
await check('dm message', () => p.validChatMessage(d.dm, null));
await check('dm decrypts for recipient', async () => {
  const b = d.dm.body;
  const value = await p.decryptChatValue(b.content, d.peerEncryptionPrivateJwk, `dm:${b.conversationId}:${b.id}:${b.recipientId}`);
  if (value.text !== 'Water at Gate 2 ✅ — पानी') throw new Error('wrong plaintext');
});
await check('conversation id', async () => {
  if ((await p.directConversationId(d.profile.body.id, d.peerProfile.body.id)) !== d.dm.body.conversationId) throw new Error('mismatch');
});
for (const [name, doc, format] of [['reply + forwarded', d.reply, 'TEXT'], ['delete for everyone', d.deletion, 'SYSTEM']])
  await check(name, async () => {
    p.validChatMessage(doc, null);
    const b = doc.body;
    const value = await p.decryptChatValue(b.content, d.peerEncryptionPrivateJwk, `dm:${b.conversationId}:${b.id}:${b.recipientId}`);
    p.validChatPayload(value, format);
    if ((value.replyTo ?? value.deletes) !== d.dm.body.id) throw new Error('wrong target');
  });
await check('join link (any requester, approval)', () => p.validChatAdmission(d.joinLink, d.peerProfile.body.id));
await check('join link is reusable for 7 days', () => p.validChatAdmission(d.joinLink, d.profile.body.id, Date.now() + 6 * 86400000));
await check('join request through the link', () => p.validChatJoin(d.linkJoin));
await check('receipt', () => p.validChatReceipt(d.receipt, d.dm, null));
await check('join', () => p.validChatJoin(d.join));
await check('channel policy (iPhone-owned, keys wrapped)', () => p.validChannelPolicy(d.policy));
await check('peer unwraps its channel key', async () => {
  const b = d.policy.body, me = d.peerProfile.body.id;
  const jwe = b.keys.find((k) => k.participantId === me).jwe;
  const v = await p.decryptChatValue(jwe, d.peerEncryptionPrivateJwk, `channel:${b.id}:${b.epoch}:${me}`);
  if (v.key !== d.channelKey) throw new Error('key mismatch');
});
await check('channel post', () => p.validChatMessage(d.post, d.policy));
await check('admin action (SET_ROLE ADMIN)', () => p.validChatAction(d.action, d.policy));
await check('invite for the peer', () => p.validChatInvite(d.invite, d.peerProfile.body.id));
await check('approval admission', () => p.validChatAdmission(d.admission, d.peerProfile.body.id));
