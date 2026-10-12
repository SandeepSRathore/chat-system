import { Component, ElementRef, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { AuthService } from '../../core/auth.service';
import { ChatSocketService } from '../../core/chat-socket.service';
import { ChatStore } from '../../core/chat-store.service';
import { ChatItem, MAX_MESSAGE_LENGTH } from '../../core/models';
import { PresenceSocketService } from '../../core/presence-socket.service';
import { ConnectionBadge } from './connection-badge';
import { ClockTimePipe, lastSeen } from './format';
import { NowService } from './now.service';

/** Distance from the bottom (px) within which new messages keep the view pinned to the latest. */
const STICKY_THRESHOLD = 120;

@Component({
  selector: 'app-conversation',
  imports: [ConnectionBadge, ClockTimePipe],
  templateUrl: './conversation.html',
  styleUrl: './conversation.scss',
})
export class Conversation {
  protected readonly store = inject(ChatStore);
  protected readonly socket = inject(ChatSocketService);
  private readonly auth = inject(AuthService);
  private readonly presence = inject(PresenceSocketService);
  private readonly now = inject(NowService).now;

  private readonly log = viewChild<ElementRef<HTMLElement>>('log');
  private readonly composer = viewChild<ElementRef<HTMLTextAreaElement>>('composer');

  protected readonly draft = signal('');
  protected readonly maxLength = MAX_MESSAGE_LENGTH;
  protected readonly me = computed(() => this.auth.user()?.id);
  protected readonly remaining = computed(() => MAX_MESSAGE_LENGTH - this.draft().length);
  protected readonly canSend = computed(() => this.draft().trim().length > 0 && this.remaining() >= 0);

  protected readonly subtitle = computed(() => {
    const channel = this.store.active();
    if (!channel) {
      return '';
    }
    const statuses = this.presence.presence();
    if (channel.type === 'DIRECT' && channel.peerId !== null) {
      const status = statuses.get(channel.peerId);
      return status?.online ? 'Online' : lastSeen(status?.lastActiveAt ?? 0, this.now());
    }
    const others = channel.members.filter((m) => m.id !== this.me());
    const online = others.filter((m) => statuses.get(m.id)?.online).length;
    return `${channel.members.length} members, ${online} online`;
  });

  protected readonly peerOnline = computed(() => {
    const channel = this.store.active();
    return channel?.peerId != null && (this.presence.presence().get(channel.peerId)?.online ?? false);
  });

  private stickToBottom = true;
  private restoreFrom: { height: number; top: number } | null = null;

  constructor() {
    // Keep the newest message in view unless the reader has scrolled up; keep position when older pages load.
    effect(() => {
      this.store.activeItems();
      const log = untracked(() => this.log())?.nativeElement;
      if (!log) {
        return;
      }
      queueMicrotask(() => {
        if (this.restoreFrom) {
          log.scrollTop = log.scrollHeight - this.restoreFrom.height + this.restoreFrom.top;
          this.restoreFrom = null;
        } else if (this.stickToBottom) {
          log.scrollTop = log.scrollHeight;
        }
      });
    });
    // A newly opened conversation starts at the bottom with the composer focused.
    effect(() => {
      if (this.store.activeId()) {
        this.stickToBottom = true;
        queueMicrotask(() => this.composer()?.nativeElement.focus());
      }
    });
  }

  protected onScroll(event: Event): void {
    const log = event.target as HTMLElement;
    this.stickToBottom = log.scrollHeight - log.scrollTop - log.clientHeight < STICKY_THRESHOLD;
    if (log.scrollTop < 40 && this.store.activeHasMore() && !this.store.loadingOlder()) {
      void this.loadOlder();
    }
  }

  protected async loadOlder(): Promise<void> {
    const log = this.log()?.nativeElement;
    if (log) {
      this.restoreFrom = { height: log.scrollHeight, top: log.scrollTop };
    }
    await this.store.loadOlder();
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      this.send();
    }
  }

  protected send(): void {
    if (!this.canSend()) {
      return;
    }
    this.store.send(this.draft());
    this.draft.set('');
    this.stickToBottom = true;
  }

  protected senderName(item: ChatItem): string {
    return this.store.active()?.members.find((m) => m.id === item.senderId)?.displayName ?? 'Former member';
  }

  /** Show the sender's name above the first of a run of their messages (groups only). */
  protected startsRun(index: number): boolean {
    const items = this.store.activeItems();
    return index === 0 || items[index - 1].senderId !== items[index].senderId;
  }
}
