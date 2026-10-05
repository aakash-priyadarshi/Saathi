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
    title: z.string().trim().min(3).max(100).optional(),
    description: z.string().trim().min(5).max(2000).optional(),
    requestedQuantity: z.number().int().positive().max(1000000).optional(),
    priority: z.enum(['NORMAL', 'HIGH', 'URGENT']).optional(),
  })
  .strict();
