import { PrismaClient } from '@prisma/client';
import { env } from '@saathi/config';
import { hashPassword, digest, token } from '@saathi/auth';
export async function seed(db: PrismaClient) {
  if (
    env.APP_ENV === 'production' ||
    env.DEMO_MODE !== 'true' ||
    (env.APP_ENV === 'staging' && process.env.STAGING_SEED_ALLOWED !== 'true')
  )
    throw new Error('Demo seed only runs with DEMO_MODE=true outside production.');
  const password = process.env.SEED_PASSWORD;
  if (!password || password.length < 12)
    throw new Error('Set SEED_PASSWORD with at least 12 characters.');
  const hash = await hashPassword(password);
  const community = await db.organization.upsert({
    where: { name: 'Community Relief Network' },
    create: { name: 'Community Relief Network', verified: true },
    update: {},
  });
  const medical = await db.organization.upsert({
    where: { name: 'Medical Volunteers Group' },
    create: { name: 'Medical Volunteers Group', verified: true },
    update: {},
  });
  const accounts = [
    { email: 'admin@saathi.test', name: 'Saathi Admin', role: 'ADMIN' as const, org: community },
    {
      email: 'coordinator@saathi.test',
      name: 'Ananya R.',
      role: 'COORDINATOR' as const,
      org: community,
    },
    { email: 'volunteer@saathi.test', name: 'Aman K.', role: 'VOLUNTEER' as const, org: community },
    {
      email: 'medical@saathi.test',
      name: 'Dr. Meera S.',
      role: 'VOLUNTEER' as const,
      org: medical,
    },
    { email: 'public@saathi.test', name: 'Public user', role: 'PUBLIC' as const, org: community },
    { email: 'android-volunteer@saathi.test', name: 'Android QA Volunteer', role: 'VOLUNTEER' as const, org: community },
  ];
  const users = [];
  for (const account of accounts) {
    const user = await db.user.upsert({
      where: { email: account.email },
      create: {
        email: account.email,
        displayName: account.name,
        role: account.role,
        passwordHash: hash,
        emailVerifiedAt: new Date(),
      },
      update: {},
    });
    users.push(user);
    if (account.role !== 'PUBLIC')
      await db.organizationMembership.upsert({
        where: { userId_organizationId: { userId: user.id, organizationId: account.org.id } },
        create: {
          userId: user.id,
          organizationId: account.org.id,
          role: account.role,
          approved: true,
        },
        update: {},
      });
    if (account.role === 'VOLUNTEER')
      await db.volunteerProfile.upsert({
        where: { userId: user.id },
        create: { userId: user.id, approvedAt: new Date() },
        update: {},
      });
  }
  if (env.APP_ENV === 'staging' && process.env.STAGING_SEED_RELIEF_DATA !== 'true') {
    console.log('Staging demo accounts ready; relief sample data seeding is disabled.');
    return;
  }
  const volunteer = users.find((u) => u.role === 'VOLUNTEER')!,
    doctor = users.find((u) => u.email === 'medical@saathi.test')!;
  const points = [];
  for (const [name, location, org] of [
    ['Point A', 'Community hall, east entrance', community],
    ['Point B', 'Riverside school, main gate', community],
    ['Medical Desk', 'Public clinic, receiving desk', medical],
  ] as const) {
    const existing = await db.reliefPoint.findFirst({ where: { name, organizationId: org.id } });
    points.push(
      existing ??
        (await db.reliefPoint.create({
          data: {
            name,
            publicLocation: location,
            organizationId: org.id,
            description: 'Designated public receiving point (demonstration).',
            instructions:
              'Demo delivery point. In a real deployment, follow the public receiving instructions here; do not send supplies to this sample location.',
            operatingHours: '8:00 AM–8:00 PM IST',
          },
        })),
    );
  }
  const [pointA, pointB, medicalDesk] = points;
  if (!pointA || !pointB || !medicalDesk) throw new Error('Seed points missing');
  const needs = [
    {
      publicId: 'SAA-7F3K92',
      title: 'Drinking water',
      description: 'Sealed 1 L bottles for families at the receiving point.',
      category: 'WATER' as const,
      requested: 500,
      committed: 250,
      received: 100,
      unit: 'bottles',
      priority: 'URGENT' as const,
      point: pointB,
      author: volunteer,
      hours: 3,
    },
    {
      publicId: 'SAA-8M2P41',
      title: 'Fresh packed meals',
      description: 'Vegetarian meals in individual, ready-to-distribute packs.',
      category: 'FOOD' as const,
      requested: 300,
      committed: 120,
      received: 0,
      unit: 'meals',
      priority: 'HIGH' as const,
      point: pointA,
      author: volunteer,
      hours: 5,
    },
    {
      publicId: 'SAA-4R9W63',
      title: 'ORS packets',
      description: 'Sealed oral rehydration packets. Please check expiry dates.',
      category: 'MEDICAL' as const,
      requested: 200,
      committed: 50,
      received: 20,
      unit: 'packets',
      priority: 'HIGH' as const,
      point: medicalDesk,
      author: doctor,
      hours: 6,
    },
    {
      publicId: 'SAA-6N8C25',
      title: 'Adult raincoats',
      description: 'Lightweight, reusable raincoats in adult sizes.',
      category: 'CLOTHING' as const,
      requested: 120,
      committed: 30,
      received: 0,
      unit: 'raincoats',
      priority: 'NORMAL' as const,
      point: pointB,
      author: volunteer,
      hours: 8,
    },
    {
      publicId: 'SAA-3B5Q87',
      title: 'Charged power banks',
      description: 'Charged power banks with USB cables for the volunteer desk.',
      category: 'POWER' as const,
      requested: 40,
      committed: 0,
      received: 0,
      unit: 'power banks',
      priority: 'NORMAL' as const,
      point: pointA,
      author: volunteer,
      hours: 12,
    },
    {
      publicId: 'SAA-2A6D19',
      title: 'First aid kits',
      description: 'Basic first aid kits have reached the medical team.',
      category: 'MEDICAL' as const,
      requested: 50,
      committed: 50,
      received: 50,
      unit: 'kits',
      priority: 'NORMAL' as const,
      point: medicalDesk,
      author: doctor,
      hours: 1,
    },
  ];
  for (const n of needs) {
    if (await db.reliefRequest.findUnique({ where: { publicId: n.publicId } })) continue;
    await db.$transaction(async (tx) => {
      const completed = n.received === n.requested,
        deadline = new Date(Date.now() + n.hours * 3600000);
      const r = await tx.reliefRequest.create({
        data: {
          publicId: n.publicId,
          title: n.title,
          description: n.description,
          category: n.category,
          requestedQuantity: n.requested,
          committedQuantity: n.committed,
          receivedQuantity: n.received,
          unit: n.unit,
          priority: n.priority,
          reliefPointId: n.point.id,
          organizationId: n.point.organizationId,
          creatorId: n.author.id,
          deadline,
          expiresAt: deadline,
          status: completed
            ? 'COMPLETED'
            : n.received > 0
              ? 'PARTIALLY_RECEIVED'
              : n.committed > 0
                ? 'PARTIALLY_COMMITTED'
                : 'OPEN',
          archivedAt: completed ? new Date() : null,
        },
      });
      if (n.committed) {
        const c = await tx.donationCommitment.create({
          data: {
            requestId: r.id,
            quantity: n.committed,
            receivedQuantity: n.received,
            tokenHash: digest(token()),
            status: completed ? 'DELIVERED' : 'ORDER_PLACED',
            provider: 'Local shop',
            externalOrderId: `DEMO-${n.publicId}`,
            eta: new Date(Date.now() + 3600000),
            expiresAt: deadline,
          },
        });
        if (n.received)
          await tx.delivery.create({
            data: { commitmentId: c.id, quantity: n.received, confirmedBy: n.author.id },
          });
      }
      await tx.auditEvent.create({
        data: {
          actorId: n.author.id,
          event: 'REQUEST_CREATED',
          entityType: 'ReliefRequest',
          entityId: r.id,
          next: { demo: true, publicId: r.publicId },
        },
      });
    });
  }
  if ((await db.fieldUpdate.count()) === 0) {
    const posts = [
      {
        caption:
          'Water distribution is underway at Point B. Sealed drinking water is still needed. Please check the linked request before sending supplies.',
        point: pointB,
        author: volunteer,
        request: 'SAA-7F3K92',
      },
      {
        caption:
          'The community kitchen is preparing the next meal distribution. Individual vegetarian meal packs make it easier for the team to distribute supplies.',
        point: pointA,
        author: volunteer,
        request: 'SAA-8M2P41',
      },
      {
        caption:
          'First aid kits have arrived at the medical desk. Thank you to everyone who helped. ORS packets are the next priority.',
        point: medicalDesk,
        author: doctor,
        request: 'SAA-4R9W63',
      },
    ];
    for (let i = 0; i < posts.length; i++) {
      const p = posts[i]!;
      const r = await db.reliefRequest.findUniqueOrThrow({ where: { publicId: p.request } });
      await db.fieldUpdate.create({
        data: {
          caption: p.caption,
          authorId: p.author.id,
          organizationId: p.point.organizationId,
          reliefPointId: p.point.id,
          requestId: r.id,
          moderation: 'APPROVED',
          createdAt: new Date(Date.now() - i * 7 * 60000),
        },
      });
    }
  }
  console.log(
    'Saathi demo data ready. Login: volunteer@saathi.test, coordinator@saathi.test, admin@saathi.test. Password: SEED_PASSWORD.',
  );
}
if (process.argv[1]?.replaceAll('\\', '/').endsWith('/seed.ts')) {
  const db = new PrismaClient({ datasourceUrl: env.DATABASE_URL });
  void seed(db)
    .catch((e) => {
      console.error(e);
      process.exitCode = 1;
    })
    .finally(() => db.$disconnect());
}
