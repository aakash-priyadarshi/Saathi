import {
  Controller,
  Get,
  Post,
  Patch,
  Put,
  Body,
  Param,
  Query,
  Req,
  Res,
  Headers,
  Inject,
  UseInterceptors,
  UploadedFile,
  Sse,
  MessageEvent,
  UnauthorizedException,
  PayloadTooLargeException,
} from '@nestjs/common';
import { Throttle } from '@nestjs/throttler';
import { createHash } from 'node:crypto';
import { rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { MAX_MEDIA_BYTES, communityText } from '@saathi/protocol';
import { FileInterceptor } from '@nestjs/platform-express';
import { ApiTags, ApiOperation, ApiHeader, ApiBody } from '@nestjs/swagger';
import { Observable, interval, map, concatMap, startWith, distinctUntilChanged } from 'rxjs';
import { z } from 'zod';
import type { Request, Response } from 'express';
import { env } from '@saathi/config';
import {
  requestSchema,
  reservationSchema,
  orderSchema,
  deliverySchema,
  fieldSchema,
  editSchema,
} from '@saathi/validation';
import { Database } from '../database';
import { AuthService } from '../auth/auth.service';
import { loginWithTurnstileSchema, verifyTurnstileResponse } from '../auth/turnstile';
import { RequestsService } from '../requests/requests.service';
import { DonationsService } from '../donations/donations.service';
import { ManagementService } from '../management/management.service';
import { MediaService, UploadFile, UPLOAD_PART_BYTES } from '../media/media.service';
import { platformFeatures } from '../public/platform-features';
const uuid = z.string().uuid();
async function readBody(req: Request, limit: number) {
  const parts: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    size += (chunk as Buffer).length;
    if (size > limit) throw new PayloadTooLargeException('Upload part is too large.');
    parts.push(chunk as Buffer);
  }
  return Buffer.concat(parts);
}
/** Guests prove ownership of their uploads with a random token only their browser holds. */
function guestOwner(req: Request) {
  const token = req.headers['x-upload-token'];
  if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(token))
    throw new UnauthorizedException('Upload token is missing.');
  return `guest:${createHash('sha256').update(token).digest('hex')}`;
}
@ApiTags('Media uploads')
@Controller('api/v1/uploads')
export class UploadsController {
  constructor(
    @Inject(AuthService) private readonly auth: AuthService,
    @Inject(MediaService) private readonly media: MediaService,
    @Inject(RequestsService) private readonly requests: RequestsService,
  ) {}
  /** Team uploads name their organization; uploads without one are guest uploads. */
  private async owner(req: Request, organizationId: string | null) {
    if (!organizationId) return guestOwner(req);
    const actor = await this.auth.actor(req, true);
    this.auth.requireOrg(actor, organizationId);
    return actor.id;
  }
  @Post()
  @Throttle({ default: { limit: 30, ttl: 3600000 } })
  @ApiOperation({ summary: 'Start a resumable photo/video upload of up to 250 MB' })
  async begin(@Req() req: Request, @Body() body: unknown) {
    const b = z
      .object({
        size: z.number().int().min(1).max(MAX_MEDIA_BYTES),
        organizationId: uuid.optional(),
      })
      .strict()
      .parse(body);
    await this.requests.assertLiveEnabled();
    const org = b.organizationId ?? null;
    return this.media.beginUpload(await this.owner(req, org), org, b.size);
  }
  @Put(':id/parts/:index') async part(
    @Req() req: Request,
    @Param('id') id: string,
    @Param('index') index: string,
  ) {
    const uploadId = uuid.parse(id),
      owner = await this.owner(req, await this.media.uploadOrganization(uploadId));
    await this.requests.assertLiveEnabled();
    return this.media.putPart(
      uploadId,
      owner,
      z.coerce.number().int().min(0).parse(index),
      await readBody(req, UPLOAD_PART_BYTES),
    );
  }
  @Post(':id/complete') async complete(@Req() req: Request, @Param('id') id: string) {
    const uploadId = uuid.parse(id),
      owner = await this.owner(req, await this.media.uploadOrganization(uploadId));
    await this.requests.assertLiveEnabled();
    return this.media.completeUpload(uploadId, owner);
  }
}
@ApiTags('Guest sharing')
@Controller('api/v1/guest')
export class GuestController {
  constructor(@Inject(RequestsService) private readonly requests: RequestsService) {}
  @Post('posts')
  @Throttle({ default: { limit: 10, ttl: 3600000 } })
  @ApiOperation({ summary: 'Submit an unverified photo/video post for administrator review' })
  publish(@Req() req: Request, @Body() body: unknown) {
    const b = z
      .object({
        caption: communityText(2000),
        area: communityText(80),
        contentWarning: z.boolean().default(false),
        mediaIds: z.array(uuid).min(1).max(6),
      })
      .strict()
      .parse(body);
    return this.requests.publishGuest(guestOwner(req), b);
  }
}
@ApiTags('Public relief')
@Controller('api/v1/public')
export class PublicController {
  constructor(
    @Inject(RequestsService) private readonly requests: RequestsService,
    @Inject(Database) private readonly db: Database,
    @Inject(ManagementService) private readonly management: ManagementService,
  ) {}
  @Get('config') async config() {
    return {
      platformName: env.PLATFORM_NAME,
      demo: env.DEMO_MODE === 'true',
      reservationMinutes: env.RESERVATION_MINUTES,
      features: await platformFeatures(this.db),
    };
  }
  @Get('requests')
  @ApiOperation({ summary: 'Browse active verified needs or completed requests' })
  list(@Query('completed') completed?: string, @Query('category') category?: string) {
    return this.requests.list(completed === 'true', category);
  }
  @Get('requests/:id') get(@Param('id') id: string) {
    return this.requests.get(id.toUpperCase());
  }
  @Get('verify/:id') verify(@Param('id') id: string) {
    return this.requests.get(id.toUpperCase());
  }
  @Get('feed') feed() {
    return this.requests.feed();
  }
  @Post('reports') report(@Body() body: unknown) {
    const b = z
      .object({
        entityType: z.enum(['ReliefRequest', 'FieldUpdate']),
        entityId: z.string().min(1).max(60),
        reason: z.string().trim().min(10).max(1000),
      })
      .strict()
      .parse(body);
    return this.management.report(b.entityType, b.entityId, b.reason);
  }
  @Sse('events') events(): Observable<MessageEvent> {
    return interval(5000).pipe(
      startWith(0),
      concatMap(async () => {
        const [r, p] = await Promise.all([
          this.db.reliefRequest.aggregate({ _sum: { version: true }, _max: { updatedAt: true } }),
          this.db.fieldUpdate.aggregate({ _count: true, _max: { createdAt: true } }),
        ]);
        return JSON.stringify({
          requests: r._sum.version,
          updatedAt: r._max.updatedAt,
          posts: p._count,
          lastPost: p._max.createdAt,
        });
      }),
      distinctUntilChanged(),
      map((data) => ({ data, type: 'change' })),
    );
  }
}
@ApiTags('Authentication')
@Controller('api/v1/auth')
export class AuthController {
  constructor(
    @Inject(AuthService) private readonly auth: AuthService,
    @Inject(ManagementService) private readonly management: ManagementService,
  ) {}
  @Post('login')
  @Throttle({ default: { limit: 10, ttl: 60000 } })
  @ApiBody({
    schema: {
      type: 'object',
      required: ['email', 'password'],
      properties: {
        email: { type: 'string', format: 'email' },
        password: { type: 'string' },
        totp: { type: 'string' },
        turnstileToken: { type: 'string' },
      },
    },
  })
  async login(
    @Body() body: unknown,
    @Req() req: Request,
    @Res({ passthrough: true }) res: Response,
  ) {
    const b = loginWithTurnstileSchema(env.APP_ENV).parse(body);
    if (env.APP_ENV !== 'development')
      await verifyTurnstileResponse(b.turnstileToken!, {
        secret: env.TURNSTILE_SECRET,
        hostnames: env.TURNSTILE_HOSTNAMES.split(',')
          .map((host) => host.trim())
          .filter(Boolean),
        appEnv: env.APP_ENV,
      });
    return this.auth.login(b.email, b.password, b.totp, req, res);
  }
  @Get('me') async me(@Req() req: Request) {
    const u = await this.auth.actor(req);
    return {
      id: u.id,
      displayName: u.displayName,
      email: u.email,
      role: u.role,
      memberships: u.memberships.map((m) => ({
        organizationId: m.organizationId,
        role: m.role,
        approved: m.approved,
        organization: { name: m.organization.name },
      })),
    };
  }
  @Post('logout') logout(@Req() req: Request, @Res({ passthrough: true }) res: Response) {
    return this.auth.logout(req, res);
  }
  @Get('sessions') async sessions(@Req() req: Request) {
    return this.management.listSessions(await this.auth.actor(req));
  }
  @Post('sessions/:id/revoke') async revoke(@Req() req: Request, @Param('id') id: string) {
    return this.management.revokeSession(await this.auth.actor(req, true), uuid.parse(id));
  }
}
@ApiTags('Donations')
@ApiHeader({
  name: 'Idempotency-Key',
  description: 'A new UUID for each distinct write; reuse it on retry',
})
@Controller('api/v1/donations')
export class DonationsController {
  constructor(@Inject(DonationsService) private readonly donations: DonationsService) {}
  @Post() @ApiOperation({ summary: 'Reserve available quantity without a donor account' }) reserve(
    @Body() body: unknown,
    @Headers('idempotency-key') key: string,
  ) {
    const b = reservationSchema.parse(body);
    return this.donations.reserve(b.publicId, b.quantity, b.email, key ?? '');
  }
  @Get('tracking/:token') tracking(@Param('token') raw: string) {
    return this.donations.tracking(z.string().min(40).max(100).parse(raw));
  }
  @Post('tracking/:token/order') order(
    @Param('token') raw: string,
    @Body() body: unknown,
    @Headers('idempotency-key') key: string,
  ) {
    return this.donations.order(raw, orderSchema.parse(body), key ?? '');
  }
  @Post('tracking/:token/cancel') cancel(
    @Param('token') raw: string,
    @Headers('idempotency-key') key: string,
  ) {
    return this.donations.cancel(raw, key ?? '');
  }
}
@ApiTags('Volunteer')
@Controller('api/v1/volunteer')
export class VolunteerController {
  constructor(
    @Inject(AuthService) private readonly auth: AuthService,
    @Inject(RequestsService) private readonly requests: RequestsService,
    @Inject(DonationsService) private readonly donations: DonationsService,
    @Inject(ManagementService) private readonly management: ManagementService,
    @Inject(MediaService) private readonly media: MediaService,
  ) {}
  @Get('dashboard') async dashboard(@Req() req: Request) {
    return this.management.dashboard(await this.auth.actor(req));
  }
  @Post('requests') async create(@Req() req: Request, @Body() body: unknown) {
    return this.requests.create(await this.auth.actor(req, true), requestSchema.parse(body));
  }
  @Patch('requests/:id') async edit(
    @Req() req: Request,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    return this.requests.edit(await this.auth.actor(req, true), id, editSchema.parse(body));
  }
  @Post('feed') async publish(@Req() req: Request, @Body() body: unknown) {
    return this.requests.publish(await this.auth.actor(req, true), fieldSchema.parse(body));
  }
  @Post('deliveries/:id/confirm') async confirm(
    @Req() req: Request,
    @Param('id') id: string,
    @Headers('idempotency-key') key: string,
  ) {
    return this.donations.confirm(await this.auth.actor(req, true), uuid.parse(id), key ?? '');
  }
  @Post('deliveries/:id/transit') async transit(
    @Req() req: Request,
    @Param('id') id: string,
    @Headers('idempotency-key') key: string,
  ) {
    return this.donations.inTransit(await this.auth.actor(req, true), uuid.parse(id), key ?? '');
  }
  @Post('deliveries/:id/receive') async receive(
    @Req() req: Request,
    @Param('id') id: string,
    @Body() body: unknown,
    @Headers('idempotency-key') key: string,
  ) {
    const b = deliverySchema.parse(body);
    return this.donations.receive(
      await this.auth.actor(req, true),
      uuid.parse(id),
      b.quantity,
      b.version,
      key ?? '',
    );
  }
  @Post('media')
  @UseInterceptors(
    // Disk storage: a 250 MB file must not be buffered in API memory.
    FileInterceptor('file', {
      dest: tmpdir(),
      limits: { fileSize: MAX_MEDIA_BYTES, files: 1, fields: 1 },
    }),
  )
  async upload(
    @Req() req: Request,
    @UploadedFile() file: UploadFile,
    @Body('organizationId') org: string,
  ) {
    try {
      await this.requests.assertLiveEnabled();
      return await this.media.upload(await this.auth.actor(req, true), uuid.parse(org), file);
    } finally {
      if (file?.path) await rm(file.path, { force: true });
    }
  }
  @Post('media/:id/retry') async retry(@Req() req: Request, @Param('id') id: string) {
    await this.requests.assertLiveEnabled();
    return this.media.retry(await this.auth.actor(req, true), uuid.parse(id));
  }
}
@ApiTags('Coordinator and administration')
@Controller('api/v1')
export class ManagementController {
  constructor(
    @Inject(AuthService) private readonly auth: AuthService,
    @Inject(ManagementService) private readonly management: ManagementService,
    @Inject(RequestsService) private readonly requests: RequestsService,
    @Inject(MediaService) private readonly media: MediaService,
  ) {}
  @Get('coordinator/volunteers') async volunteers(@Req() req: Request) {
    return this.management.volunteers(await this.auth.actor(req));
  }
  @Post('coordinator/volunteers/:id/approve') async approve(
    @Req() req: Request,
    @Param('id') id: string,
  ) {
    return this.management.approve(await this.auth.actor(req, true), uuid.parse(id));
  }
  @Post('coordinator/volunteers/:id/suspend') async suspend(
    @Req() req: Request,
    @Param('id') id: string,
  ) {
    return this.management.approve(await this.auth.actor(req, true), uuid.parse(id), true);
  }
  @Post('coordinator/invite') async invite(@Req() req: Request, @Body() body: unknown) {
    const b = z
      .object({
        email: z.string().email(),
        displayName: z.string().min(2).max(60),
        password: z.string().min(12).max(256),
        organizationId: uuid,
        role: z.enum(['VOLUNTEER', 'COORDINATOR']),
      })
      .strict()
      .parse(body);
    return this.auth.invite(
      await this.auth.actor(req, true),
      b.email,
      b.displayName,
      b.password,
      b.organizationId,
      b.role,
    );
  }
  @Post('coordinator/points') async point(@Req() req: Request, @Body() body: unknown) {
    const b = z
      .object({
        organizationId: uuid,
        name: z.string().min(3).max(100),
        description: z.string().min(3).max(1000),
        publicLocation: z.string().min(3).max(200),
        instructions: z.string().min(3).max(1000),
        operatingHours: z.string().min(3).max(100),
      })
      .strict()
      .parse(body);
    return this.management.createPoint(await this.auth.actor(req, true), b);
  }
  @Post('coordinator/requests/:id/cancel') async cancel(
    @Req() req: Request,
    @Param('id') id: string,
  ) {
    return this.requests.cancel(await this.auth.actor(req, true), id);
  }
  @Get('coordinator/moderation') async moderation(@Req() req: Request) {
    return this.management.moderation(await this.auth.actor(req));
  }
  @Post('coordinator/moderation/:id') async moderate(
    @Req() req: Request,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    const b = z
      .object({ action: z.enum(['APPROVED', 'REJECTED', 'HIDDEN']) })
      .strict()
      .parse(body);
    return this.management.moderate(await this.auth.actor(req, true), uuid.parse(id), b.action);
  }
  @Get('coordinator/media/:id/original') async original(
    @Req() req: Request,
    @Param('id') id: string,
  ) {
    return this.media.original(await this.auth.actor(req), uuid.parse(id));
  }
  @Get('admin/organizations') async orgs(@Req() req: Request) {
    return this.management.organizations(await this.auth.actor(req));
  }
  @Post('admin/organizations') async createOrg(@Req() req: Request, @Body() body: unknown) {
    const b = z
      .object({ name: z.string().trim().min(3).max(100) })
      .strict()
      .parse(body);
    return this.management.createOrg(await this.auth.actor(req, true), b.name);
  }
  @Post('admin/organizations/:id/status') async orgStatus(
    @Req() req: Request,
    @Param('id') id: string,
    @Body() body: unknown,
  ) {
    const b = z.object({ active: z.boolean() }).strict().parse(body);
    return this.management.setOrgActive(await this.auth.actor(req, true), uuid.parse(id), b.active);
  }
  @Get('admin/audits') async audits(@Req() req: Request) {
    return this.management.audits(await this.auth.actor(req));
  }
  @Get('admin/features') async features(@Req() req: Request) {
    return this.management.features(await this.auth.actor(req));
  }
  @Patch('admin/features/needs') async setNeedsFeature(@Req() req: Request, @Body() body: unknown) {
    const b = z.object({ enabled: z.boolean() }).strict().parse(body);
    return this.management.setNeedsFeature(await this.auth.actor(req, true), b.enabled);
  }
  @Patch('admin/features/live') async setLiveFeature(@Req() req: Request, @Body() body: unknown) {
    const b = z.object({ enabled: z.boolean() }).strict().parse(body);
    return this.management.setLiveFeature(await this.auth.actor(req, true), b.enabled);
  }
  @Get('admin/reports') async reports(@Req() req: Request) {
    return this.management.reports(await this.auth.actor(req));
  }
  @Post('admin/reports/:id/resolve') async resolve(@Req() req: Request, @Param('id') id: string) {
    return this.management.resolveReport(await this.auth.actor(req, true), uuid.parse(id));
  }
}
