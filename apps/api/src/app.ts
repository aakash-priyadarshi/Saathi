import 'reflect-metadata';
import {
  Module,
  Catch,
  ExceptionFilter,
  ArgumentsHost,
  HttpException,
  Logger,
} from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { APP_GUARD } from '@nestjs/core';
import { ThrottlerGuard, ThrottlerModule } from '@nestjs/throttler';
import { SwaggerModule, DocumentBuilder } from '@nestjs/swagger';
import { ZodError } from 'zod';
import { randomUUID } from 'node:crypto';
import cookieParser from 'cookie-parser';
import helmet from 'helmet';
import type { Request, Response, NextFunction } from 'express';
import { env } from '@saathi/config';
import { Database } from './database';
import { AuthService } from './auth/auth.service';
import { RequestsService } from './requests/requests.service';
import { DonationsService } from './donations/donations.service';
import { ManagementService } from './management/management.service';
import { Jobs } from './jobs/notifications';
import { MediaService } from './media/media.service';
import { S3Storage } from './media/storage';
import { SyncService } from './sync/sync.service';
import { SyncController } from './sync/sync.controller';
import { PublicReadModule } from './public/public.module';
import { ChatController } from './chat/chat.controller';
import { ChatService } from './chat/chat.service';
import {
  PublicController,
  AuthController,
  DonationsController,
  VolunteerController,
  ManagementController,
} from './http/controllers';
@Catch()
class Errors implements ExceptionFilter {
  catch(error: unknown, host: ArgumentsHost) {
    const res = host.switchToHttp().getResponse<Response>();
    const req = host.switchToHttp().getRequest<Request>();
    const status =
      error instanceof ZodError ? 400 : error instanceof HttpException ? error.getStatus() : 500;
    const detail =
      error instanceof ZodError
        ? error.issues.map((i) => `${i.path.join('.')}: ${i.message}`).join('; ')
        : error instanceof HttpException
          ? error.message
          : 'Something went wrong. Try again.';
    if (status >= 500)
      new Logger('Errors').error(
        JSON.stringify({
          event: 'request.error',
          requestId: res.getHeader('X-Request-ID'),
          path: req.route?.path ?? 'unmatched',
          error: String(error),
        }),
      );
    res
      .status(status)
      .json({ statusCode: status, message: detail, requestId: res.getHeader('X-Request-ID') });
  }
}
@Module({
  imports: [
    ThrottlerModule.forRoot([{ ttl: 60000, limit: env.NODE_ENV === 'test' ? 10000 : 120 }]),
  ],
  controllers: [
    ChatController,
    SyncController,
    PublicController,
    AuthController,
    DonationsController,
    VolunteerController,
    ManagementController,
  ],
  providers: [
    { provide: ChatService, useFactory: (db: Database) => new ChatService(db), inject: [Database] },
    {
      provide: SyncService,
      useFactory: (
        db: Database,
        auth: AuthService,
        requests: RequestsService,
        media: MediaService,
      ) => new SyncService(db, auth, requests, media),
      inject: [Database, AuthService, RequestsService, MediaService],
    },
    Database,
    S3Storage,
    { provide: AuthService, useFactory: (db: Database) => new AuthService(db), inject: [Database] },
    {
      provide: RequestsService,
      useFactory: (db: Database, auth: AuthService) => new RequestsService(db, auth),
      inject: [Database, AuthService],
    },
    {
      provide: DonationsService,
      useFactory: (db: Database, auth: AuthService) => new DonationsService(db, auth),
      inject: [Database, AuthService],
    },
    {
      provide: MediaService,
      useFactory: (db: Database, auth: AuthService, storage: S3Storage) =>
        new MediaService(db, auth, storage),
      inject: [Database, AuthService, S3Storage],
    },
    {
      provide: ManagementService,
      useFactory: (db: Database, auth: AuthService, media: MediaService) =>
        new ManagementService(db, auth, media),
      inject: [Database, AuthService, MediaService],
    },
    {
      provide: Jobs,
      useFactory: (db: Database, donations: DonationsService) => new Jobs(db, donations),
      inject: [Database, DonationsService],
    },
    { provide: APP_GUARD, useClass: ThrottlerGuard },
  ],
})
export class AppModule {}
export async function createApp(plane: typeof env.API_PLANE = env.API_PLANE) {
  const app = await NestFactory.create(plane === 'public' ? PublicReadModule : AppModule, {
    logger: ['error', 'warn', 'log'],
  });
  app.enableShutdownHooks();
  app.use(cookieParser());
  app.use(helmet({ crossOriginResourcePolicy: { policy: 'cross-origin' } }));
  app.enableCors({
    origin: env.WEB_ORIGIN,
    credentials: true,
    allowedHeaders: ['Content-Type', 'Idempotency-Key', 'X-CSRF-Token'],
  });
  app.use((req: Request, res: Response, next: NextFunction) => {
    const requestId = randomUUID(),
      started = Date.now();
    res.setHeader('X-Request-ID', requestId);
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Referrer-Policy', 'no-referrer');
    if (
      plane === 'public' &&
      (req.path === '/metrics' ||
        req.path === '/api/docs' ||
        !['GET', 'HEAD', 'OPTIONS'].includes(req.method))
    ) {
      res.status(404).json({ message: 'Not found.' });
      return;
    }
    res.on('finish', () =>
      new Logger('HTTP').log(
        JSON.stringify({
          requestId,
          method: req.method,
          route:
            req.route?.path ??
            (req.path.includes('/tracking/') ? '/api/v1/donations/tracking/[private]' : req.path),
          status: res.statusCode,
          durationMs: Date.now() - started,
        }),
      ),
    );
    if (!['GET', 'HEAD', 'OPTIONS'].includes(req.method) && req.headers.origin !== env.WEB_ORIGIN) {
      res.status(403).json({ message: 'Request origin is not allowed.', requestId });
      return;
    }
    next();
  });
  app.useGlobalFilters(new Errors());
  const express = app.getHttpAdapter().getInstance();
  if (env.STORAGE_PROVIDER === 'local' && plane !== 'public') {
    express.get('/api/v1/public/media/:folder/:key', async (req: Request, res: Response) => {
      try {
        const key = `${req.params.folder}/${req.params.key}`;
        const bytes = await app.get(S3Storage).readPublic(key);
        res.type(key.endsWith('.mp4') ? 'video/mp4' : 'image/jpeg').send(bytes);
      } catch {
        res.sendStatus(404);
      }
    });
    express.get('/api/v1/local-original', async (req: Request, res: Response) => {
      const { key, expires, signature } = req.query;
      if (
        typeof key !== 'string' ||
        typeof signature !== 'string' ||
        !app.get(S3Storage).validSignature(key, Number(expires), signature)
      ) {
        res.sendStatus(403);
        return;
      }
      try {
        res.type('application/octet-stream').send(await app.get(S3Storage).readPrivate(key));
      } catch {
        res.sendStatus(404);
      }
    });
  }
  express.get('/health', (_req: Request, res: Response) => res.json({ status: 'ok' }));
  express.get('/ready', async (_req: Request, res: Response) => {
    try {
      await app.get(Database).$queryRaw`SELECT 1`;
      res.json({ status: 'ready' });
    } catch {
      res.status(503).json({ status: 'unavailable' });
    }
  });
  express.get('/metrics', async (req: Request, res: Response) => {
    try {
      const actor = await app.get(AuthService).actor(req);
      if (actor.role !== 'ADMIN') {
        res.sendStatus(403);
        return;
      }
      const db = app.get(Database);
      const [active, completed, donations, expired, pending, mediaFailures, offline, observations] =
        await Promise.all([
          db.reliefRequest.count({
            where: { status: { notIn: ['DRAFT', 'COMPLETED', 'CANCELLED', 'EXPIRED'] } },
          }),
          db.reliefRequest.count({ where: { status: 'COMPLETED' } }),
          db.donationCommitment.count(),
          db.donationCommitment.count({ where: { status: 'EXPIRED' } }),
          db.notification.count({ where: { status: 'PENDING' } }),
          db.mediaAsset.count({ where: { processingState: 'FAILED' } }),
          db.offlineEvent.groupBy({ by: ['status'], _count: true }),
          db.syncReceipt.aggregate({ _count: true, _sum: { observations: true } }),
        ]);
      res
        .type('text/plain')
        .send(
          `active_requests ${active}\ncompleted_requests ${completed}\ndonations_created ${donations}\nreservations_expired ${expired}\nnotifications_pending ${pending}\nmedia_processing_failures ${mediaFailures}\nrelay_paths_observed ${observations._count}\nrelay_delivery_attempts ${observations._sum.observations ?? 0}\n${offline.map((row) => `offline_events{status="${row.status}"} ${row._count}`).join('\n')}\n`,
        );
    } catch {
      res.sendStatus(401);
    }
  });
  if (plane !== 'public') {
    const doc = SwaggerModule.createDocument(
      app,
      new DocumentBuilder()
        .setTitle(`${env.PLATFORM_NAME} API`)
        .setVersion('1.0')
        .addCookieAuth('saathi_session')
        .build(),
    );
    SwaggerModule.setup('api/docs', app, doc);
  }
  return app;
}
