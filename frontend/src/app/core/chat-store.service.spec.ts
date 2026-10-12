import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Subject } from 'rxjs';
import { vi } from 'vitest';
import { ApiService } from './api.service';
import { AuthService } from './auth.service';
import { ChatSocketService } from './chat-socket.service';
import { ChatStore } from './chat-store.service';
import { Channel, ChatMessage, ChatServerFrame } from './models';
import { PresenceSocketService } from './presence-socket.service';

const ME = { id: 1, username: 'alice', displayName: 'Alice' };
const BOB = { id: 2, username: 'bob', displayName: 'Bob' };
const DM: Channel = {
  id: 'dm:1:2', type: 'DIRECT', name: 'Bob', peerId: 2, groupId: null, ownerId: null, members: [ME, BOB], lastMessage: null,
};
const GROUP: Channel = {
  id: 'g:7', type: 'GROUP', name: 'Team', peerId: null, groupId: 7, ownerId: 1, members: [ME, BOB], lastMessage: null,
};

const msg = (channelId: string, messageId: number, senderId: number, content = `m${messageId}`): ChatMessage => ({
  channelId, messageId, senderId, content, createdAt: 1_000 * messageId,
});

describe('ChatStore', () => {
  let store: ChatStore;
  let frames: Subject<ChatServerFrame>;
  let sent: object[];
  let api: Record<'channels' | 'history' | 'presence' | 'friendRequests' | 'acceptFriendRequest', ReturnType<typeof vi.fn>>;

  beforeEach(async () => {
    frames = new Subject();
    sent = [];
    api = {
      channels: vi.fn().mockResolvedValue([DM, GROUP]),
      history: vi.fn().mockResolvedValue([]),
      presence: vi.fn().mockResolvedValue([]),
      friendRequests: vi.fn().mockResolvedValue({ incoming: [], outgoing: [] }),
      acceptFriendRequest: vi.fn().mockResolvedValue(BOB),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiService, useValue: api },
        { provide: AuthService, useValue: { user: signal(ME) } },
        {
          provide: ChatSocketService,
          useValue: {
            frames$: frames, connected$: new Subject<void>(), start: vi.fn(), stop: vi.fn(),
            send: (f: object) => { sent.push(f); return true; },
          },
        },
        {
          provide: PresenceSocketService,
          useValue: { snapshots$: new Subject<void>(), start: vi.fn(), stop: vi.fn(), merge: vi.fn() },
        },
      ],
    });
    store = TestBed.inject(ChatStore);
    await store.init();
  });

  it('shows a send as pending until the chat server acknowledges it', async () => {
    store.select(DM.id);
    await Promise.resolve();
    store.send('  hello  ');

    const [frame] = sent as { clientMsgId: string; content: string }[];
    expect(frame.content).toBe('hello');
    expect(store.activeItems()[0].state).toBe('sending');

    frames.next({ type: 'ACK', clientMsgId: frame.clientMsgId, message: msg(DM.id, 5, ME.id, 'hello') });
    expect(store.activeItems()).toHaveLength(1);
    expect(store.activeItems()[0]).toMatchObject({ state: 'sent', messageId: 5, key: frame.clientMsgId });
    expect(store.channels()[0].lastMessage?.messageId).toBe(5);
  });

  it('marks a rejected send as failed and resends it on retry', async () => {
    store.select(DM.id);
    await Promise.resolve();
    store.send('nope');
    const { clientMsgId } = sent[0] as { clientMsgId: string };

    frames.next({ type: 'ERROR', clientMsgId, error: 'You are not a member of this channel' });
    expect(store.activeItems()[0]).toMatchObject({ state: 'failed', error: 'You are not a member of this channel' });

    store.retry(store.activeItems()[0]);
    expect(store.activeItems()[0].state).toBe('sending');
    expect(sent).toHaveLength(2);
    expect((sent[1] as { clientMsgId: string }).clientMsgId).toBe(clientMsgId);
  });

  it('orders messages by their per-channel id and ignores duplicates', async () => {
    api.history.mockResolvedValue([msg(DM.id, 2, 2), msg(DM.id, 3, 1)]);
    store.select(DM.id);
    await vi.waitFor(() => expect(store.activeItems()).toHaveLength(2));

    frames.next({ type: 'MESSAGE', message: msg(DM.id, 1, 2) });
    frames.next({ type: 'MESSAGE', message: msg(DM.id, 3, 1) });
    expect(store.activeItems().map((i) => i.messageId)).toEqual([1, 2, 3]);
  });

  it('counts unread messages for conversations that are not open', () => {
    store.select(DM.id);
    frames.next({ type: 'MESSAGE', message: msg(GROUP.id, 1, 2) });
    frames.next({ type: 'MESSAGE', message: msg(GROUP.id, 2, 2) });
    expect(store.unread()[GROUP.id]).toBe(2);
    expect(store.channels()[0].id).toBe(GROUP.id);

    store.select(GROUP.id);
    expect(store.unread()[GROUP.id]).toBe(0);
  });

  it('accepting a friend request opens the new conversation', async () => {
    api.friendRequests.mockResolvedValueOnce({ incoming: [BOB], outgoing: [] });
    await store.refreshFriendRequests();
    expect(store.friendRequests().incoming).toEqual([BOB]);

    await store.acceptFriend(BOB);
    expect(api.acceptFriendRequest).toHaveBeenCalledWith(BOB.id);
    expect(store.friendRequests().incoming).toEqual([]);
    expect(store.activeId()).toBe('dm:1:2');
  });

  it('fetches presence for group members on demand', () => {
    store.select(GROUP.id);
    expect(api.presence).toHaveBeenCalledWith([BOB.id]);
  });
});
