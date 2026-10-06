import { Body, Controller, Inject, Post, Get, Param, Req } from '@nestjs/common';
import type { Request } from 'express';
import { z } from 'zod';
import { AuthService } from '../auth/auth.service';
import { CommunityService } from './community.service';
@Controller('api/v1/community')
export class CommunityController {
  constructor(
    @Inject(CommunityService) private readonly community: CommunityService,
    @Inject(AuthService) private readonly auth: AuthService,
  ) {}
  @Post('sync') exchange(@Body() input: unknown) {
    return this.community.exchange(input);
  }
  @Post('attachment') attachment(@Body() input: unknown) {
    return this.community.attachment(input);
  }
  @Get('moderation') async moderation(@Req() req: Request) {
    return this.community.moderation(await this.auth.actor(req));
  }
  @Post('moderation/:id/hide') async hide(@Req() req: Request, @Param('id') id: string) {
    return this.community.hide(await this.auth.actor(req, true), z.string().uuid().parse(id));
  }
}
