import {
  Injectable,
  UnauthorizedException,
  ForbiddenException,
  BadRequestException,
} from '@nestjs/common';
import { Database, audit } from '../database';
import { digest, token, verifyPassword, hashPassword, verifyTotp } from '@saathi/auth';
import { env } from '@saathi/config';
import type { Request, Response } from 'express';
import type { Prisma } from '@saathi/database';
export const actorInclude = {
  memberships: { include: { organization: true } },
  volunteer: true,
} satisfies Prisma.UserInclude;
export type Actor = Prisma.UserGetPayload<{ include: typeof actorInclude }>;
@Injectable()
export class AuthService {
  constructor(private readonly db: Database) {}
  async login(
    email: string,
    password: string,
    totp: string | undefined,
    req: Request,
    res: Response,
  ) {
    const key = digest(email);
    const attempt = await this.db.loginAttempt.findUnique({ where: { key } });
    if (attempt?.lockedUntil && attempt.lockedUntil > new Date())
      throw new UnauthorizedException('Too many attempts. Try again in 15 minutes.');
    const user = await this.db.user.findUnique({ where: { email } });
    // The dummy hash gives unknown accounts the same expensive password verification path.
    const dummy =
      'scrypt-v1:00000000000000000000000000000000:8cf90d3e8748f2dfd4ad0c8b646d688d69b9b17ec0d1bf6210c8e3ef34061f5b0c426d4b4c036f04b2e7c16ad6bddf3d1ae947533010b47b8b4c18b0a0bfc5a8';
    const valid = await verifyPassword(password, user?.passwordHash ?? dummy);
    if (
      !user ||
      !valid ||
      !user.active ||
      !user.emailVerifiedAt ||
      (user.totpSecret && (!totp || !verifyTotp(user.totpSecret, totp)))
    ) {
      const failed = await this.db.loginAttempt.upsert({
        where: { key },
        create: { key, failures: 1 },
        update: { failures: { increment: 1 } },
      });
      if (failed.failures >= 5)
        await this.db.loginAttempt.update({
          where: { key },
          data: { lockedUntil: new Date(Date.now() + 900000) },
        });
      throw new UnauthorizedException('Email, password, or verification code is incorrect.');
    }
    await this.db.loginAttempt.deleteMany({ where: { key } });
    if (user.passwordHash.startsWith('scrypt:'))
      await this.db.user.update({
        where: { id: user.id },
        data: { passwordHash: await hashPassword(password) },
      });
    const raw = token(),
      csrf = token();
    await this.db.atomic(async (tx) => {
      await tx.session.create({
        data: {
          userId: user.id,
          tokenHash: digest(raw),
          csrfHash: digest(csrf),
          label: (req.headers['user-agent'] ?? 'Device').slice(0, 160),
          expiresAt: new Date(Date.now() + env.SESSION_DAYS * 86400000),
        },
      });
      await audit(tx, 'SESSION_CREATED', 'User', user.id, user.id);
    });
    const opts = {
      secure: env.NODE_ENV === 'production',
      sameSite: 'strict' as const,
      path: '/',
      maxAge: env.SESSION_DAYS * 86400000,
    };
    res.cookie('saathi_session', raw, { ...opts, httpOnly: true });
    res.cookie('saathi_csrf', csrf, { ...opts, httpOnly: false });
    return { ok: true };
  }
  async actor(req: Request, write = false): Promise<Actor> {
    const raw =
      typeof req.cookies?.saathi_session === 'string' ? req.cookies.saathi_session : undefined;
    if (!raw) throw new UnauthorizedException('Sign in to continue.');
    const session = await this.db.session.findUnique({
      where: { tokenHash: digest(raw) },
      include: { user: { include: actorInclude } },
    });
    if (!session || session.revokedAt || session.expiresAt < new Date() || !session.user.active)
      throw new UnauthorizedException('Session expired. Sign in again.');
    if (write) {
      const csrf = req.headers['x-csrf-token'];
      if (typeof csrf !== 'string' || digest(csrf) !== session.csrfHash)
        throw new ForbiddenException('Invalid security token. Refresh and try again.');
    }
    return session.user;
  }
  requireOrg(actor: Actor, organizationId: string | null, coordinator = false) {
    if (actor.role === 'ADMIN') return;
    const membership = actor.memberships.find(
      (m) =>
        m.organizationId === organizationId &&
        m.approved &&
        m.organization.active &&
        m.organization.verified,
    );
    if (
      !membership ||
      actor.role === 'PUBLIC' ||
      (coordinator && membership.role !== 'COORDINATOR') ||
      (!coordinator &&
        membership.role === 'VOLUNTEER' &&
        (!actor.volunteer?.approvedAt || actor.volunteer.suspendedAt))
    )
      throw new ForbiddenException('Your verified organization does not permit this action.');
  }
  async logout(req: Request, res: Response) {
    const actor = await this.actor(req, true);
    await this.db.session.updateMany({
      where: { tokenHash: digest(req.cookies.saathi_session) },
      data: { revokedAt: new Date() },
    });
    res.clearCookie('saathi_session', { path: '/' });
    res.clearCookie('saathi_csrf', { path: '/' });
    return { ok: true, userId: actor.id };
  }
  async invite(
    actor: Actor,
    email: string,
    displayName: string,
    password: string,
    organizationId: string,
    role: 'VOLUNTEER' | 'COORDINATOR',
  ) {
    this.requireOrg(actor, organizationId, true);
    if (role === 'COORDINATOR' && actor.role !== 'ADMIN')
      throw new ForbiddenException('Only an admin can appoint coordinators.');
    if (password.length < 12) throw new BadRequestException('Use at least 12 characters.');
    const passwordHash = await hashPassword(password);
    return this.db.atomic(async (tx) => {
      const user = await tx.user.create({
        data: {
          email: email.toLowerCase(),
          displayName,
          passwordHash,
          role,
          emailVerifiedAt: role === 'COORDINATOR' ? new Date() : null,
          memberships: { create: { organizationId, role, approved: role === 'COORDINATOR' } },
          volunteer: role === 'VOLUNTEER' ? { create: {} } : undefined,
        },
      });
      await audit(tx, 'USER_INVITED', 'User', user.id, actor.id, undefined, {
        organizationId,
        role,
      });
      return { id: user.id, displayName: user.displayName };
    });
  }
}
