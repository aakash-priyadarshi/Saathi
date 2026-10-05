import { describe, it, expect, vi } from 'vitest';
import { S3Storage } from '../../apps/api/src/media/storage';

type Page = {
  Contents?: { Key?: string; LastModified?: Date }[];
  IsTruncated?: boolean;
  NextContinuationToken?: string;
};
async function withS3(operation: (storage: S3Storage) => Promise<void>) {
  const storage = new S3Storage();
  Reflect.set(storage, 'provider', 's3');
  try {
    await operation(storage);
  } finally {
    vi.restoreAllMocks();
  }
}
function sender(storage: S3Storage) {
  return (
    storage as unknown as {
      client: { send(command: { input: Record<string, unknown> }): Promise<Page> };
    }
  ).client;
}
describe('S3 media inventory provider responses', () => {
  it('follows continuation tokens and excludes unrelated or malformed object keys', async () => {
    await withS3(async (storage) => {
      const first = 'original/11111111-1111-4111-8111-111111111111';
      const second = 'original/22222222-2222-4222-8222-222222222222';
      const modified = new Date('2026-01-01T00:00:00Z');
      const send = vi.spyOn(sender(storage), 'send').mockImplementation(async (command) => {
        expect(command.input.MaxKeys).toBe(200);
        if (command.input.Prefix === 'sanitized/') return {};
        if (command.input.ContinuationToken === 'second-page')
          return { Contents: [{ Key: second, LastModified: modified }] };
        return {
          Contents: [
            { Key: first, LastModified: modified },
            { Key: 'other/11111111', LastModified: modified },
            { Key: 'original/../private', LastModified: modified },
            { Key: second },
          ],
          IsTruncated: true,
          NextContinuationToken: 'second-page',
        };
      });
      const objects = [];
      for await (const object of storage.inventory('private')) objects.push(object);
      expect(objects).toEqual([
        { key: first, modifiedAt: modified },
        { key: second, modifiedAt: modified },
      ]);
      expect(send).toHaveBeenCalledTimes(3);
    });
  });
  it('fails instead of looping or reporting success when truncated pagination cannot advance', async () => {
    await withS3(async (storage) => {
      const send = vi
        .spyOn(sender(storage), 'send')
        .mockResolvedValue({ IsTruncated: true, NextContinuationToken: 'same' });
      const read = async () => {
        for await (const object of storage.inventory('public')) void object;
      };
      await expect(read()).rejects.toThrow('pagination did not advance');
      expect(send).toHaveBeenCalledTimes(2);
    });
  });
});
