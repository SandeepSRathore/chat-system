import { Component, computed, inject, output, signal, viewChild } from '@angular/core';
import { ApiService, errorMessage } from '../../core/api.service';
import { ChatStore } from '../../core/chat-store.service';
import { User } from '../../core/models';
import { Modal } from './modal';

@Component({
  selector: 'app-add-friend-dialog',
  imports: [Modal],
  template: `
    <app-modal heading="Add a friend" titleId="add-friend-title" (closed)="closed.emit()">
      <div class="field">
        <label for="friend-search">Username or name</label>
        <input id="friend-search" type="search" autocomplete="off" autofocus
               [value]="query()" (input)="onQuery($any($event.target).value)" />
      </div>

      @if (error(); as message) {
        <p class="form-error" role="alert">{{ message }}</p>
      }

      <ul class="results" aria-live="polite">
        @for (user of results(); track user.id) {
          <li>
            <span class="who">
              <span class="name">{{ user.displayName }}</span>
              <span class="handle">&#64;{{ user.username }}</span>
            </span>
            @switch (relation(user)) {
              @case ('friend') {
                <button type="button" class="btn btn-quiet" (click)="open(user)">Open chat</button>
              }
              @case ('incoming') {
                <button type="button" class="btn btn-primary" [disabled]="busy() === user.id" (click)="accept(user)">
                  Accept request
                </button>
              }
              @case ('outgoing') {
                <span class="pending">Request sent</span>
                <button type="button" class="btn btn-quiet" [disabled]="busy() === user.id" (click)="withdraw(user)">
                  Withdraw
                </button>
              }
              @default {
                <button type="button" class="btn btn-primary" [disabled]="busy() === user.id" (click)="request(user)">
                  {{ busy() === user.id ? 'Sending…' : 'Send request' }}
                </button>
              }
            }
          </li>
        } @empty {
          @if (searched()) {
            <li class="none">No one matches "{{ query() }}".</li>
          }
        }
      </ul>
    </app-modal>
  `,
  styles: `
    .results { list-style: none; margin: 0; padding: 0; display: grid; gap: 0.25rem; max-height: 18rem; overflow-y: auto; }
    li { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: 0.4rem 0; }
    .who { display: grid; min-width: 0; }
    .name { font-weight: 500; }
    .handle { color: var(--ink-muted); font-size: var(--step--1); }
    .none { color: var(--ink-muted); }
    .pending { color: var(--ink-muted); font-size: var(--step--1); margin-left: auto; }
  `,
})
export class AddFriendDialog {
  private readonly api = inject(ApiService);
  private readonly store = inject(ChatStore);
  private readonly modal = viewChild.required(Modal);

  readonly closed = output<void>();

  protected readonly query = signal('');
  protected readonly results = signal<User[]>([]);
  protected readonly searched = signal(false);
  protected readonly busy = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  private readonly friendIds = computed(
    () => new Set(this.store.channels().flatMap((c) => (c.peerId !== null ? [c.peerId] : []))),
  );

  protected relation(user: User): 'friend' | 'incoming' | 'outgoing' | 'none' {
    if (this.friendIds().has(user.id)) {
      return 'friend';
    }
    const { incoming, outgoing } = this.store.friendRequests();
    if (incoming.some((u) => u.id === user.id)) {
      return 'incoming';
    }
    return outgoing.some((u) => u.id === user.id) ? 'outgoing' : 'none';
  }

  private debounce?: ReturnType<typeof setTimeout>;

  protected onQuery(value: string): void {
    this.query.set(value);
    clearTimeout(this.debounce);
    if (!value.trim()) {
      this.results.set([]);
      this.searched.set(false);
      return;
    }
    this.debounce = setTimeout(async () => {
      try {
        this.results.set(await this.api.searchUsers(value.trim()));
        this.searched.set(true);
        this.error.set(null);
      } catch (e) {
        this.error.set(errorMessage(e, 'Search failed. Try again.'));
      }
    }, 250);
  }

  protected async request(user: User): Promise<void> {
    await this.run(user, async () => {
      const result = await this.api.sendFriendRequest(user.id);
      if (result.status === 'ACCEPTED') {
        // They had already asked us, so this completed the friendship.
        await this.store.refreshChannels();
        this.open(user);
      }
      await this.store.refreshFriendRequests();
    }, `The request to ${user.displayName} could not be sent.`);
  }

  protected async accept(user: User): Promise<void> {
    await this.run(user, async () => {
      await this.store.acceptFriend(user);
      this.modal().close();
    }, `The request from ${user.displayName} could not be accepted.`);
  }

  protected async withdraw(user: User): Promise<void> {
    await this.run(user, () => this.store.declineFriend(user), 'The request could not be withdrawn.');
  }

  private async run(user: User, action: () => Promise<void>, failure: string): Promise<void> {
    this.busy.set(user.id);
    this.error.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(errorMessage(e, failure));
    } finally {
      this.busy.set(null);
    }
  }

  protected open(user: User): void {
    this.store.select(this.store.directChannelId(user.id));
    this.modal().close();
  }
}
