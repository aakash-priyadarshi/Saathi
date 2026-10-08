import { describe, expect, it } from 'vitest';
import { reliefPointSchema } from '../../packages/validation/src';
import { lookupIndianPostalCode } from '../../apps/api/src/public/postal-code-lookup';
import { formatPublicAddress } from '../../apps/api/src/management/relief-point-address';
import { publicRequest } from '../../apps/api/src/public/public-read.service';

const point = {
  organizationId: '2e2e35ca-2bab-4b96-b016-0f3c8621b6d6',
  name: 'Jantar Mantar Gate 2',
  description: 'Public receiving point outside Gate 2.',
  addressLine1: 'Sansad Marg',
  locality: 'Jantar Mantar',
  postalCode: '110001',
  city: 'New Delhi',
  district: 'New Delhi',
  state: 'Delhi',
  country: 'India',
  instructions: 'Meet the volunteer at the public handoff desk.',
  operatingHours: 'Daily, 9 am–6 pm IST',
};

describe('relief point address', () => {
  it('requires a six-digit PIN and a paired coordinate when publishing an exact pin', () => {
    expect(reliefPointSchema.parse(point)).toMatchObject({ exactLocationApproved: false });
    expect(reliefPointSchema.safeParse({ ...point, postalCode: '11001' }).success).toBe(false);
    expect(
      reliefPointSchema.safeParse({ ...point, latitude: 28.61, exactLocationApproved: true })
        .success,
    ).toBe(false);
  });

  it('accepts an explicit, valid exact public pin only with both coordinates', () => {
    expect(
      reliefPointSchema.parse({
        ...point,
        latitude: 28.6139,
        longitude: 77.209,
        exactLocationApproved: true,
      }),
    ).toMatchObject({ exactLocationApproved: true, latitude: 28.6139, longitude: 77.209 });
    expect(reliefPointSchema.safeParse({ ...point, latitude: 91, longitude: 77.209 }).success).toBe(
      false,
    );
  });

  it('formats the PIN before the city and avoids repeating an identical district', () => {
    expect(formatPublicAddress({ ...point, landmark: 'Gate 2' })).toBe(
      'Sansad Marg, Jantar Mantar, Gate 2, PIN 110001, New Delhi, Delhi, India',
    );
  });

  it('looks up Indian locality details without exposing PIN-centroid coordinates as an exact pin', async () => {
    await expect(lookupIndianPostalCode('110001')).resolves.toMatchObject({
      valid: true,
      place: 'New Delhi G.P.O.',
      district: 'New Delhi',
      state: 'Delhi',
    });
    await expect(lookupIndianPostalCode('000000')).resolves.toEqual({ valid: false });
    await expect(lookupIndianPostalCode('11001')).resolves.toEqual({ valid: false });
  });

  it('does not attach a relief-point map pin to a request-specific delivery address', () => {
    const pointRequest = {
      publicId: 'SAA-7F3K92',
      title: 'Drinking water',
      description: 'Sealed bottles for the public meeting point.',
      category: 'WATER',
      unit: 'bottles',
      requestedQuantity: 10,
      committedQuantity: 0,
      receivedQuantity: 0,
      status: 'OPEN',
      expiresAt: new Date(Date.now() + 60_000),
      deadline: new Date(Date.now() + 60_000),
      updatedAt: new Date(),
      version: 1,
      deliveryLocation: 'Alternate public gate',
      organization: { name: 'Relief Team', verified: true, active: true },
      creator: { displayName: 'Coordinator' },
      reliefPoint: {
        name: 'Main gate',
        publicLocation: 'Jantar Mantar, New Delhi',
        instructions: 'Meet at the public desk.',
        operatingHours: '9 am–6 pm',
        exactLocationApproved: true,
        latitude: 28.6139,
        longitude: 77.209,
      },
    } as Parameters<typeof publicRequest>[0];
    expect(publicRequest(pointRequest).reliefPoint).not.toHaveProperty('latitude');
  });
});
