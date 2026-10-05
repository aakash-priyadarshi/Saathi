import { Controller, Get, Param, Query, Inject, Module } from '@nestjs/common';
import { APP_GUARD } from '@nestjs/core';
import { ThrottlerGuard, ThrottlerModule } from '@nestjs/throttler';
import { Database } from '../database';
import { PublicReadService } from './public-read.service';
import { env } from '@saathi/config';

@Controller('api/v1/public')
export class ReadOnlyPublicController {
  constructor(@Inject(PublicReadService) private readonly read: PublicReadService) {}
  @Get('config') config() {
    return {
      platformName: env.PLATFORM_NAME,
      demo: env.DEMO_MODE === 'true',
      reservationMinutes: env.RESERVATION_MINUTES,
    };
  }
  @Get('requests') list(
    @Query('completed') completed?: string,
    @Query('category') category?: string,
  ) {
    return this.read.list(completed === 'true', category);
  }
  @Get('requests/:id') get(@Param('id') id: string) {
    return this.read.get(id.toUpperCase());
  }
  @Get('verify/:id') verify(@Param('id') id: string) {
    return this.read.get(id.toUpperCase());
  }
  @Get('feed') feed() {
    return this.read.feed();
  }
}
@Module({
  imports: [ThrottlerModule.forRoot([{ ttl: 60000, limit: 120 }])],
  controllers: [ReadOnlyPublicController],
  providers: [
    Database,
    {
      provide: PublicReadService,
      useFactory: (db: Database) => new PublicReadService(db),
      inject: [Database],
    },
    { provide: APP_GUARD, useClass: ThrottlerGuard },
  ],
})
export class PublicReadModule {}
