import type { RequestStatus } from '@saathi/types';
export const closed = new Set<RequestStatus>(['COMPLETED', 'CANCELLED', 'EXPIRED']);
export function deriveStatus(
  requested: number,
  committed: number,
  received: number,
  current: RequestStatus,
): RequestStatus {
  if (closed.has(current)) return current;
  if (received >= requested) return 'COMPLETED';
  if (received > 0) return 'PARTIALLY_RECEIVED';
  if (committed >= requested) return 'FULLY_COMMITTED';
  if (committed > 0) return 'PARTIALLY_COMMITTED';
  return 'OPEN';
}
export function assertQuantities(requested: number, committed: number, received: number) {
  if (
    !Number.isInteger(requested) ||
    requested <= 0 ||
    received < 0 ||
    committed < received ||
    committed > requested
  )
    throw new Error('Invalid fulfillment quantities');
}
