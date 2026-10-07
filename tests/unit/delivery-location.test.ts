import { describe, expect, it } from 'vitest';
import { editSchema, requestPayloadSchema } from '../../packages/validation/src';

const request = {
  reliefPointId: '2e2e35ca-2bab-4b96-b016-0f3c8621b6d6',
  category: 'WATER',
  title: 'Drinking water',
  description: 'Sealed bottles for people at the public meeting point.',
  requestedQuantity: 100,
  unit: 'bottles',
  priority: 'NORMAL',
  deadline: '2030-01-01T12:00:00.000Z',
};

describe('public delivery location', () => {
  it('accepts a request-specific public handoff point and an omitted override', () => {
    expect(requestPayloadSchema.parse({ ...request, deliveryLocation: 'Gate 2, Jantar Mantar' }))
      .toHaveProperty('deliveryLocation', 'Gate 2, Jantar Mantar');
    expect(requestPayloadSchema.parse(request)).not.toHaveProperty('deliveryLocation');
  });

  it('allows clearing an override and rejects unsafe-sized or vague values', () => {
    expect(requestPayloadSchema.parse({ ...request, deliveryLocation: null }))
      .toHaveProperty('deliveryLocation', null);
    expect(requestPayloadSchema.safeParse({ ...request, deliveryLocation: 'AB' }).success).toBe(false);
    expect(requestPayloadSchema.safeParse({ ...request, deliveryLocation: 'x'.repeat(201) }).success).toBe(false);
    expect(editSchema.parse({ version: 1, deliveryLocation: null })).toEqual({ version: 1, deliveryLocation: null });
  });
});
