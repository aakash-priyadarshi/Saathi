import { Controller, Get, Post, Param, Req, Body, Inject } from '@nestjs/common';
import { z } from 'zod';
import type { Request } from 'express';
import { publicKeySchema } from '@saathi/protocol';
import { AuthService } from '../auth/auth.service';
import { SyncService } from './sync.service';
@Controller('api/v1/sync')
export class SyncController {
  constructor(
    @Inject(AuthService) private readonly auth: AuthService,
    @Inject(SyncService) private readonly sync: SyncService,
  ) {}
  @Get('receipt-key') key() {
    return this.sync.receiptKey();
  }
  @Get('preparation') async preparation(@Req() req: Request) {
    return this.sync.prepare(await this.auth.actor(req));
  }
  @Post('devices') async device(@Req() req: Request, @Body() value: unknown) {
    return this.sync.register(await this.auth.actor(req, true), publicKeySchema.parse(value));
  }
  @Get('devices') async devices(@Req() req: Request) {
    return this.sync.devices(await this.auth.actor(req));
  }
  @Post('devices/:id/revoke') async revoke(@Req() req: Request, @Param('id') id: string) {
    return this.sync.revoke(await this.auth.actor(req, true), z.string().uuid().parse(id));
  }
  @Post('events') event(@Body() value: unknown) {
    const parsed = z
      .object({ envelope: z.unknown(), carrierId: z.string().uuid() })
      .strict()
      .parse(value);
    return this.sync.ingest(parsed.envelope, parsed.carrierId);
  }
  @Post('receipts') receipts(@Body() value: unknown) {
    return this.sync.receipts(
      z
        .object({ ids: z.array(z.string().uuid()).max(50) })
        .strict()
        .parse(value).ids,
    );
  }
  @Post('events/:id/invalidate') async invalidate(@Req() req: Request, @Param('id') id: string) {
    return this.sync.invalidate(await this.auth.actor(req, true), z.string().uuid().parse(id));
  }
  @Post('events/:id/media') async media(
    @Req() req: Request,
    @Param('id') id: string,
    @Body() value: unknown,
  ) {
    const input = z
      .object({
        mediaIds: z
          .array(z.string().uuid())
          .min(1)
          .max(4)
          .refine((ids) => new Set(ids).size === ids.length),
      })
      .strict()
      .parse(value);
    return this.sync.attachMedia(
      await this.auth.actor(req, true),
      z.string().uuid().parse(id),
      input.mediaIds,
    );
  }
}
