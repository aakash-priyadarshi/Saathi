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
await check('receipt', () => p.validChatReceipt(d.receipt, d.dm, null));
await check('join', () => p.validChatJoin(d.join));
