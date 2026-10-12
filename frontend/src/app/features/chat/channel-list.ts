import { Component, computed, inject, output, signal } from '@angular/core';
import { AuthService } from '../../core/auth.service';
import { ChatStore } from '../../core/chat-store.service';
import { Channel, User } from '../../core/models';
import { errorMessage } from '../../core/api.service';
import { PresenceSocketService } from '../../core/presence-socket.service';
import { ShortTimePipe, initials } from './format';
import { NowService } from './now.service';

@Component({
  selector: 'app-channel-list',
  imports: [ShortTimePipe],
  templateUrl: './channel-list.html',
  styleUrl: './channel-list.scss',
})
export class ChannelList {
  protected readonly store = inject(ChatStore);
  protected readonly auth = inject(AuthService);
  private readonly presence = inject(PresenceSocketService);
  protected readonly now = inject(NowService).now;

  readonly addFriend = output<void>();
  readonly newGroup = output<void>();
  readonly signOut = output<void>();

  protected readonly filter = signal('');
  protected readonly visible = computed(() => {
    const q = this.filter().trim().toLowerCase();
    const channels = this.store.channels();
    return q ? channels.filter((c) => c.name.toLowerCase().includes(q)) : channels;
  });

  protected readonly initials = initials;
  protected readonly requestError = signal<string | null>(null);

  protected async accept(user: User): Promise<void> {
    await this.answer(() => this.store.acceptFriend(user), `The request from ${user.displayName} could not be accepted.`);
  }

  protected async decline(user: User): Promise<void> {
    await this.answer(() => this.store.declineFriend(user), `The request from ${user.displayName} could not be declined.`);
  }

  private async answer(action: () => Promise<void>, failure: string): Promise<void> {
    this.requestError.set(null);
    try {
      await action();
    } catch (e) {
      this.requestError.set(errorMessage(e, failure));
    }
  }

  protected isOnline(channel: Channel): boolean {
    return channel.peerId !== null && (this.presence.presence().get(channel.peerId)?.online ?? false);
  }

  protected preview(channel: Channel): string {
    const last = channel.lastMessage;
    if (!last) {
      return channel.type === 'GROUP' ? `${channel.members.length} members` : 'No messages yet';
    }
    const mine = last.senderId === this.auth.user()?.id;
    if (mine) {
      return `You: ${last.content}`;
    }
    if (channel.type === 'GROUP') {
      const sender = channel.members.find((m) => m.id === last.senderId)?.displayName ?? 'Someone';
      return `${sender}: ${last.content}`;
    }
    return last.content;
  }
}
