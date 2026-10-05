export const categories = [
  'FOOD',
  'WATER',
  'MEDICAL',
  'HYGIENE',
  'CLOTHING',
  'POWER',
  'SHELTER',
  'OTHER',
] as const;
export type Category = (typeof categories)[number];
export type RequestStatus =
  | 'DRAFT'
  | 'OPEN'
  | 'PARTIALLY_COMMITTED'
  | 'FULLY_COMMITTED'
  | 'CONFIRMED'
  | 'IN_TRANSIT'
  | 'PARTIALLY_RECEIVED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'EXPIRED';
export const terminalStatuses: RequestStatus[] = ['COMPLETED', 'CANCELLED', 'EXPIRED'];
export type PublicPoint = {
  name: string;
  publicLocation: string;
  instructions: string;
  operatingHours: string;
  latitude?: number;
  longitude?: number;
};
export type PublicRequest = {
  publicId: string;
  title: string;
  description: string;
  category: Category;
  unit: string;
  requestedQuantity: number;
  committedQuantity: number;
  receivedQuantity: number;
  remainingQuantity: number;
  priority: 'NORMAL' | 'HIGH' | 'URGENT';
  status: RequestStatus;
  deadline: string;
  updatedAt: string;
  version: number;
  canonicalUrl: string;
  organization: { name: string; verified: boolean };
  creator: { displayName: string };
  reliefPoint: PublicPoint;
};
export type PublicPost = {
  id: string;
  caption: string;
  createdAt: string;
  organization: { name: string; verified: boolean };
  author: { displayName: string };
  reliefPoint: { name: string; publicLocation: string };
  requestPublicId: string | null;
  media: { id: string; url: string; thumbnailUrl: string | null; mimeType: string }[];
};
export type CurrentUser = {
  id: string;
  displayName: string;
  email: string;
  role: 'PUBLIC' | 'VOLUNTEER' | 'COORDINATOR' | 'ADMIN';
  memberships: {
    organizationId: string;
    role: string;
    approved: boolean;
    organization: { name: string };
  }[];
};
