import { Body, Controller, Inject, Post } from '@nestjs/common';
import { ChatService } from './chat.service';
@Controller('api/v1/chat')
export class ChatController {
  constructor(@Inject(ChatService) private readonly chat: ChatService) {}
  @Post('sync') sync(@Body() input: unknown) {
    return this.chat.sync(input);
  }
  @Post('attachment') attachment(@Body() input: unknown) {
    return this.chat.attachment(input);
  }
}
