import { describe, expect, it, vi } from 'vitest';
import { bootstrapAdmin } from '../../packages/database/src/admin-bootstrap';

type FakeUser = {
  id: string;
  email: string;
  role: string;
  displayName: string;
  passwordHash: string;
};

function fixture(initialUsers: FakeUser[] = []) {
  const users = [...initialUsers];
  const tx = {
    $queryRaw: vi.fn(async (..._query: unknown[]) => []),
    user: {
      findUnique: vi.fn(
        async ({ where }: { where: { email: string } }) =>
          users.find((user) => user.email === where.email) ?? null,
      ),
      findMany: vi.fn(async () => users.filter((user) => user.role === 'ADMIN')),
      create: vi.fn(async ({ data }: { data: Omit<FakeUser, 'id'> }) => {
        const user = { ...data, id: 'created-admin' };
        users.push(user);
        return { id: user.id, email: user.email };
      }),
      update: vi.fn(async ({ where, data }: { where: { id: string }; data: Partial<FakeUser> }) => {
        const user = users.find((item) => item.id === where.id)!;
        Object.assign(user, data);
        return { id: user.id, email: user.email };
      }),
    },
    session: { updateMany: vi.fn(async () => ({ count: 1 })) },
    auditEvent: { create: vi.fn(async () => ({})) },
  };
  const transaction = vi.fn(async (operation: (client: object) => Promise<unknown>) =>
    operation(tx),
  );
  return {
    tx,
    users,
    db: { $transaction: transaction } as unknown as Parameters<typeof bootstrapAdmin>[0],
  };
}

const account = {
  email: 'admin@example.org',
  displayName: 'Site Administrator',
  passwordHash: 'hashed-initial-password',
};

describe('admin bootstrap', () => {
  it('creates the first administrator under a transaction-scoped lock and audits it', async () => {
    const { db, tx } = fixture();
    await expect(bootstrapAdmin(db, account)).resolves.toEqual({
      id: 'created-admin',
      email: account.email,
    });
    expect(tx.$queryRaw).toHaveBeenCalledOnce();
    const queryStrings = tx.$queryRaw.mock.calls[0]?.[0] as string[] | undefined;
    expect(queryStrings?.join('?')).toContain('pg_advisory_xact_lock(?::int, ?::int) IS NULL');
    expect(tx.user.create).toHaveBeenCalledOnce();
    expect(tx.auditEvent.create).toHaveBeenCalledOnce();
  });

  it('replaces only the seeded admin fixture and revokes its existing sessions', async () => {
    const fixtureData = fixture([
      {
        id: 'demo-admin',
        email: 'admin@saathi.test',
        role: 'ADMIN',
        displayName: 'Demo Admin',
        passwordHash: 'old-hash',
      },
    ]);
    await bootstrapAdmin(fixtureData.db, account);
    expect(fixtureData.users.filter((user) => user.role === 'ADMIN')).toHaveLength(1);
    expect(fixtureData.users[0]?.email).toBe(account.email);
    expect(fixtureData.tx.user.create).not.toHaveBeenCalled();
    expect(fixtureData.tx.user.update).toHaveBeenCalledOnce();
    expect(fixtureData.tx.session.updateMany).toHaveBeenCalledOnce();
  });

  it('refuses to create another administrator when a real admin already exists', async () => {
    const { db, tx } = fixture([
      {
        id: 'existing-admin',
        email: 'owner@example.org',
        role: 'ADMIN',
        displayName: 'Existing Admin',
        passwordHash: 'old-hash',
      },
    ]);
    await expect(bootstrapAdmin(db, account)).rejects.toThrow(
      'An administrator already exists; no changes were made.',
    );
    expect(tx.user.create).not.toHaveBeenCalled();
    expect(tx.user.update).not.toHaveBeenCalled();
  });

  it('does not overwrite an account that already owns the requested email', async () => {
    const { db, tx } = fixture([
      {
        id: 'existing-account',
        email: account.email,
        role: 'PUBLIC',
        displayName: 'Account Owner',
        passwordHash: 'old-hash',
      },
    ]);
    await expect(bootstrapAdmin(db, account)).rejects.toThrow(
      'That email already has an account; no changes were made.',
    );
    expect(tx.user.create).not.toHaveBeenCalled();
    expect(tx.user.update).not.toHaveBeenCalled();
  });
});
