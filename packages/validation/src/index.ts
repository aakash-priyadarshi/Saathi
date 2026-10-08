import { z } from 'zod';
import { categories } from '@saathi/types';
export const loginSchema = z
  .object({
    email: z
      .string()
      .email()
      .max(254)
      .transform((v) => v.toLowerCase()),
    password: z.string().min(1).max(256),
    totp: z
      .string()
      .regex(/^\d{6}$/)
      .optional(),
  })
  .strict();
export const requestPayloadSchema = z
  .object({
    reliefPointId: z.string().uuid(),
    deliveryLocation: z.string().trim().min(3).max(200).nullable().optional(),
    category: z.enum(categories),
    title: z.string().trim().min(3).max(100),
    description: z.string().trim().min(5).max(2000),
    requestedQuantity: z.number().int().positive().max(1000000),
    unit: z.string().trim().min(1).max(30),
    priority: z.enum(['NORMAL', 'HIGH', 'URGENT']),
    deadline: z.string().datetime(),
    expiresAt: z.string().datetime().optional(),
  })
  .strict();
export const requestSchema = requestPayloadSchema.refine((v) => new Date(v.deadline) > new Date(), {
  message: 'Deadline must be in the future',
  path: ['deadline'],
});
export const reservationSchema = z
  .object({
    publicId: z.string().regex(/^[A-Z]{2,6}-[A-Z0-9]{6,12}$/),
    quantity: z.number().int().positive().max(1000000),
    email: z.string().email().max(254).optional(),
  })
  .strict();
export const orderSchema = z
  .object({
    provider: z.enum(['Zomato', 'Swiggy', 'Local shop', 'Courier', 'Self delivery', 'Other']),
    externalOrderId: z.string().trim().min(1).max(100),
    eta: z.string().datetime(),
    notes: z.string().trim().max(1000).optional(),
  })
  .strict();
export const deliverySchema = z
  .object({ quantity: z.number().int().positive(), version: z.number().int().positive() })
  .strict();
export const fieldSchema = z
  .object({
    caption: z.string().trim().min(5).max(4000),
    reliefPointId: z.string().uuid(),
    requestPublicId: z.string().optional(),
    mediaIds: z.array(z.string().uuid()).max(6).default([]),
    publishAt: z.string().datetime().optional(),
    request: requestSchema.optional(),
  })
  .strict();
export const editSchema = z
  .object({
    version: z.number().int().positive(),
    deliveryLocation: z.string().trim().min(3).max(200).nullable().optional(),
    title: z.string().trim().min(3).max(100).optional(),
    description: z.string().trim().min(5).max(2000).optional(),
    requestedQuantity: z.number().int().positive().max(1000000).optional(),
    priority: z.enum(['NORMAL', 'HIGH', 'URGENT']).optional(),
  })
  .strict();

export const reliefPointSchema = z
  .object({
    organizationId: z.string().uuid(),
    name: z.string().trim().min(3).max(100),
    description: z.string().trim().min(3).max(1000),
    addressLine1: z.string().trim().min(3).max(120),
    locality: z.string().trim().min(2).max(80),
    landmark: z.string().trim().max(100).optional(),
    postalCode: z.string().regex(/^\d{6}$/),
    city: z.string().trim().min(2).max(60),
    district: z.string().trim().min(2).max(60),
    state: z.string().trim().min(2).max(60),
    country: z.literal('India'),
    instructions: z.string().trim().min(3).max(1000),
    operatingHours: z.string().trim().min(3).max(100),
    latitude: z.number().finite().min(-90).max(90).optional(),
    longitude: z.number().finite().min(-180).max(180).optional(),
    exactLocationApproved: z.boolean().default(false),
  })
  .strict()
  .superRefine((point, ctx) => {
    if ((point.latitude === undefined) !== (point.longitude === undefined))
      ctx.addIssue({ code: 'custom', message: 'Enter both map coordinates.', path: ['latitude'] });
    if (
      point.exactLocationApproved &&
      (point.latitude === undefined || point.longitude === undefined)
    )
      ctx.addIssue({
        code: 'custom',
        message: 'Add the exact map coordinates before publishing them.',
        path: ['exactLocationApproved'],
      });
  });
