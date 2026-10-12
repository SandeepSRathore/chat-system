import { Service, computed, inject, signal } from '@angular/core';
import { Subscription, skip } from 'rxjs';
import { ApiService } from './api.service';
import { AuthService } from './auth.service';
import { ChatSocketService } from './chat-socket.service';
import { Channel, ChatItem, ChatMessage, ChatServerFrame } from './models';
import { PresenceSocketService } from './presence-socket.service';

const PAGE_SIZE = 50;

/** Conversation state for the signed-in user: channels, messages per channel and send outbox. */
@Service()
export class ChatStore {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly chatSocket = inject(ChatSocketService);
  private readonly presenceSocket = inject(PresenceSocketService);

  readonly channels = signal<Channel[]>([]);
  readonly channelsLoaded = signal(false);
  readonly activeId = signal<string | null>(null);
  readonly unread = signal<Readonly<Record<string, number>>>({});
  readonly loadingOlder = signal(false);
  readonly loadError = signal<string | null>(null);

  private readonly items = signal<Readonly<Record<string, ChatItem[]>>>({});
  private readonly exhausted = signal<ReadonlySet<string>>(new Set());
  private subscriptions?: Subscription;

  readonly active = computed(() => this.channels().find((c) => c.id === this.activeId()) ?? null);
  readonly activeItems = computed(() => {
    const id = this.activeId();
    return id ? (this.items()[id] ?? []) : [];
  });
  readonly activeHasMore = computed(() => {
    const id = this.activeId();
    return id !== null && !this.exhausted().has(id);
  });

  async init(): Promise<void> {
    this.subscriptions = new Subscription();
    this.subscriptions.add(this.chatSocket.frames$.subscribe((frame) => this.onFrame(frame)));
    this.subscriptions.add(this.chatSocket.connected$.subscribe(() => void this.resync()));
    // Someone befriended us: the presence server pushes a fresh snapshot, so pick up the new conversation.
    this.subscriptions.add(this.presenceSocket.snapshots$.pipe(skip(1)).subscribe(() => void this.refreshChannels()));
    this.chatSocket.start();
    this.presenceSocket.start();
    await this.refreshChannels();
  }

  destroy(): void {
    this.subscriptions?.unsubscribe();
    this.chatSocket.stop();
    this.presenceSocket.stop();
    this.channels.set([]);
    this.channelsLoaded.set(false);
    this.items.set({});
    this.unread.set({});
    this.exhausted.set(new Set());
    this.activeId.set(null);
  }

  async refreshChannels(): Promise<void> {
    try {
      this.channels.set(await this.api.channels());
      this.loadError.set(null);
    } catch {
      this.loadError.set('Conversations could not be loaded. Retrying when the connection returns.');
    } finally {
      this.channelsLoaded.set(true);
    }
  }

  select(channelId: string | null): void {
    this.activeId.set(channelId);
    if (!channelId) {
      return;
    }
    this.unread.update((u) => ({ ...u, [channelId]: 0 }));
    if (!this.items()[channelId]) {
      void this.loadLatest(channelId);
    }
    const channel = this.channels().find((c) => c.id === channelId);
    if (channel?.type === 'GROUP') {
      // Book: presence of group members is fetched on demand, not pushed.
      const others = channel.members.map((m) => m.id).filter((id) => id !== this.auth.user()?.id);
      if (others.length) {
        this.api.presence(others).then(
          (statuses) => this.presenceSocket.merge(statuses),
          () => undefined,
        );
      }
    }
  }

  send(content: string): void {
    const channelId = this.activeId();
    const me = this.auth.user();
    const text = content.trim();
    if (!channelId || !me || !text) {
      return;
    }
    const clientMsgId = crypto.randomUUID();
    this.upsert(channelId, {
      key: clientMsgId,
      channelId,
      clientMsgId,
      senderId: me.id,
      content: text,
      createdAt: Date.now(),
      state: 'sending',
    });
    // If the socket is down the item stays "sending" and goes out on reconnect.
    this.chatSocket.send({ type: 'SEND', clientMsgId, channelId, content: text });
  }

  retry(item: ChatItem): void {
    if (!item.clientMsgId) {
      return;
    }
    this.upsert(item.channelId, { ...item, state: 'sending', error: undefined });
    this.chatSocket.send({ type: 'SEND', clientMsgId: item.clientMsgId, channelId: item.channelId, content: item.content });
  }

  async loadOlder(): Promise<void> {
    const channelId = this.activeId();
    if (!channelId || this.loadingOlder() || this.exhausted().has(channelId)) {
      return;
    }
    const oldest = (this.items()[channelId] ?? []).find((i) => i.messageId !== undefined)?.messageId;
    this.loadingOlder.set(true);
    try {
      const page = await this.api.history(channelId, oldest, PAGE_SIZE);
      this.mergeHistory(channelId, page);
    } finally {
      this.loadingOlder.set(false);
    }
  }

  private async loadLatest(channelId: string): Promise<void> {
    try {
      this.mergeHistory(channelId, await this.api.history(channelId, undefined, PAGE_SIZE));
    } catch {
      this.items.update((all) => ({ ...all, [channelId]: all[channelId] ?? [] }));
    }
  }

  /** After a (re)connect: catch up on what arrived while offline and flush the outbox. */
  private async resync(): Promise<void> {
    await this.refreshChannels();
    const active = this.activeId();
    if (active) {
      await this.loadLatest(active);
    }
    for (const list of Object.values(this.items())) {
      for (const item of list) {
        if (item.state === 'sending' && item.clientMsgId) {
          this.chatSocket.send({ type: 'SEND', clientMsgId: item.clientMsgId, channelId: item.channelId, content: item.content });
        }
      }
    }
  }

  private onFrame(frame: ChatServerFrame): void {
    switch (frame.type) {
      case 'ACK':
        this.upsert(frame.message.channelId, {
          ...this.toItem(frame.message, 'sent'),
          key: frame.clientMsgId,
          clientMsgId: frame.clientMsgId,
        });
        this.touchChannel(frame.message);
        break;
      case 'MESSAGE':
        this.onIncoming(frame.message);
        break;
      case 'ERROR':
        if (frame.clientMsgId) {
          this.markFailed(frame.clientMsgId, frame.error);
        }
        break;
    }
  }

  private onIncoming(message: ChatMessage): void {
    if (!this.channels().some((c) => c.id === message.channelId)) {
      // Someone befriended us or added us to a group since we loaded the list.
      void this.refreshChannels();
    }
    if (this.items()[message.channelId]) {
      this.upsert(message.channelId, this.toItem(message, 'received'));
    }
    if (message.channelId !== this.activeId()) {
      this.unread.update((u) => ({ ...u, [message.channelId]: (u[message.channelId] ?? 0) + 1 }));
    }
    this.touchChannel(message);
  }

  private mergeHistory(channelId: string, page: ChatMessage[]): void {
    const me = this.auth.user()?.id;
    if (page.length < PAGE_SIZE) {
      this.exhausted.update((s) => new Set(s).add(channelId));
    }
    for (const message of page) {
      this.upsert(channelId, this.toItem(message, message.senderId === me ? 'sent' : 'received'));
    }
    this.items.update((all) => ({ ...all, [channelId]: all[channelId] ?? [] }));
  }

  /** Inserts or replaces by message id (or client id for my unacknowledged sends), keeping order. */
  private upsert(channelId: string, item: ChatItem): void {
    this.items.update((all) => {
      const list = [...(all[channelId] ?? [])];
      const index = list.findIndex(
        (i) =>
          (item.clientMsgId !== undefined && i.clientMsgId === item.clientMsgId) ||
          (item.messageId !== undefined && i.messageId === item.messageId),
      );
      if (index >= 0) {
        // Keep the rendering key stable so the bubble doesn't re-mount when its ACK arrives.
        list[index] = { ...list[index], ...item, key: list[index].key };
      } else {
        list.push(item);
      }
      list.sort(byDeliveryOrder);
      return { ...all, [channelId]: list };
    });
  }

  private markFailed(clientMsgId: string, error: string): void {
    this.items.update((all) => {
      const next: Record<string, ChatItem[]> = {};
      for (const [id, list] of Object.entries(all)) {
        next[id] = list.map((i) => (i.clientMsgId === clientMsgId ? { ...i, state: 'failed', error } : i));
      }
      return next;
    });
  }

  private touchChannel(message: ChatMessage): void {
    this.channels.update((channels) =>
      channels
        .map((c) =>
          c.id === message.channelId && (c.lastMessage?.messageId ?? 0) <= message.messageId
            ? { ...c, lastMessage: message }
            : c,
        )
        .sort((a, b) => (b.lastMessage?.createdAt ?? 0) - (a.lastMessage?.createdAt ?? 0)),
    );
  }

  private toItem(message: ChatMessage, state: ChatItem['state']): ChatItem {
    return {
      key: `m${message.messageId}`,
      channelId: message.channelId,
      messageId: message.messageId,
      senderId: message.senderId,
      content: message.content,
      createdAt: message.createdAt,
      state,
    };
  }
}

/** Server-ordered messages first (by per-channel id), then my in-flight sends in the order typed. */
function byDeliveryOrder(a: ChatItem, b: ChatItem): number {
  if (a.messageId !== undefined && b.messageId !== undefined) {
    return a.messageId - b.messageId;
  }
  if (a.messageId !== undefined) {
    return -1;
  }
  if (b.messageId !== undefined) {
    return 1;
  }
  return a.createdAt - b.createdAt;
}
