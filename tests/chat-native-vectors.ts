/** Public interoperability fixtures only; generated throwaway keys are never used by an app/account. */
import { writeFile } from 'node:fs/promises';
import { chatPerson, chatPolicy, chatMessage } from './chat-fixtures';
async function main() {
  const a = await chatPerson('Public test Alice'),
    b = await chatPerson('Public test Bob'),
    privateChannel = await chatPolicy(a, [a, b], 'INVITE'),
    openChannel = await chatPolicy(a, [a, b], 'OPEN');
  const dm = await chatMessage(a, b),
    privateMessage = await chatMessage(a, privateChannel),
    openMessage = await chatMessage(a, openChannel);
  await writeFile(
    'apps/android/app/src/test/resources/chat-vectors.json',
    JSON.stringify(
      {
        note: 'PUBLIC THROWAWAY TEST KEYS. Not a user identity or deployment credential.',
        now: new Date().toISOString(),
        recipientPrivateJwk: await crypto.subtle.exportKey('jwk', b.ecdh.privateKey),
        dm,
        privateChannel: privateChannel.policy,
        privateMessage,
        openChannel: openChannel.policy,
        openMessage,
        key: Buffer.from(privateChannel.key).toString('base64url'),
      },
      null,
      2,
    ) + '\n',
  );
  console.log('Public JOSE/native interoperability vectors generated.');
}
void main().catch(() => {
  console.error('Public chat vector generation failed.');
  process.exitCode = 1;
});
