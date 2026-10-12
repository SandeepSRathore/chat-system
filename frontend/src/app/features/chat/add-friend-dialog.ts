import { Component, computed, inject, output, signal, viewChild } from '@angular/core';
import { ApiService, errorMessage } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
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
            @if (friendIds().has(user.id)) {
              <button type="button" class="btn btn-quiet" (click)="open(user)">Open chat</button>
            } @else {
              <button type="button" class="btn btn-primary" [disabled]="adding() === user.id" (click)="add(user)">
                {{ adding() === user.id ? 'Adding…' : 'Add' }}
              </button>
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
  `,
})
export class AddFriendDialog {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly store = inject(ChatStore);
  private readonly modal = viewChild.required(Modal);

  readonly closed = output<void>();

  protected readonly query = signal('');
  protected readonly results = signal<User[]>([]);
  protected readonly searched = signal(false);
  protected readonly adding = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly friendIds = computed(
    () => new Set(this.store.channels().flatMap((c) => (c.peerId !== null ? [c.peerId] : []))),
  );

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

  protected async add(user: User): Promise<void> {
    this.adding.set(user.id);
    this.error.set(null);
    try {
      await this.api.addFriend(user.id);
      await this.store.refreshChannels();
      this.open(user);
    } catch (e) {
      this.error.set(errorMessage(e, `${user.displayName} could not be added.`));
    } finally {
      this.adding.set(null);
    }
  }

  protected open(user: User): void {
    const me = this.auth.user()!.id;
    this.store.select(`dm:${Math.min(me, user.id)}:${Math.max(me, user.id)}`);
    this.modal().close();
  }
}
