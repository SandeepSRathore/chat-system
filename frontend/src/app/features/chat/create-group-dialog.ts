import { Component, computed, inject, output, signal, viewChild } from '@angular/core';
import { FormField, form, maxLength, required, submit } from '@angular/forms/signals';
import { ApiService, errorMessage } from '../../core/api.service';
import { ChatStore } from '../../core/chat-store.service';
import { MAX_GROUP_SIZE, User } from '../../core/models';
import { Modal } from './modal';

@Component({
  selector: 'app-create-group-dialog',
  imports: [Modal, FormField],
  template: `
    <app-modal heading="New group" titleId="new-group-title" (closed)="closed.emit()">
      <form (submit)="create($event)" novalidate>
        <div class="field">
          <label for="group-name">Group name</label>
          <input id="group-name" autocomplete="off" [formField]="groupForm.name" />
          @if (groupForm.name().touched() && groupForm.name().invalid()) {
            <p class="field-error">{{ groupForm.name().errors()[0].message }}</p>
          }
        </div>

        <fieldset>
          <legend>Members <span class="count">{{ selected().size + 1 }} of {{ maxSize }}, including you</span></legend>
          @for (friend of friends(); track friend.id) {
            <label class="member">
              <input type="checkbox" [checked]="selected().has(friend.id)"
                     [disabled]="!selected().has(friend.id) && full()" (change)="toggle(friend.id)" />
              {{ friend.displayName }} <span class="handle">&#64;{{ friend.username }}</span>
            </label>
          } @empty {
            <p class="none">Add friends first; groups are made from people you know.</p>
          }
        </fieldset>

        @if (error(); as message) {
          <p class="form-error" role="alert">{{ message }}</p>
        }

        <button type="submit" class="btn btn-primary" [disabled]="busy() || selected().size === 0">
          {{ busy() ? 'Creating…' : 'Create group' }}
        </button>
      </form>
    </app-modal>
  `,
  styles: `
    form { display: grid; gap: 1rem; }
    fieldset { border: 0; margin: 0; padding: 0; display: grid; gap: 0.25rem; max-height: 16rem; overflow-y: auto; }
    legend { font-weight: 500; font-size: var(--step--1); margin-bottom: 0.4rem; display: flex; gap: 0.5rem; }
    .count { color: var(--ink-muted); font-weight: 400; }
    .member { display: flex; align-items: center; gap: 0.6rem; padding: 0.35rem 0; cursor: pointer; }
    .member input { width: 1.1rem; height: 1.1rem; accent-color: var(--teal); }
    .handle, .none { color: var(--ink-muted); font-size: var(--step--1); }
    .none { margin: 0; }
  `,
})
export class CreateGroupDialog {
  private readonly api = inject(ApiService);
  private readonly store = inject(ChatStore);
  private readonly modal = viewChild.required(Modal);

  readonly closed = output<void>();

  protected readonly maxSize = MAX_GROUP_SIZE;
  protected readonly model = signal({ name: '' });
  protected readonly groupForm = form(this.model, (path) => {
    required(path.name, { message: 'Give the group a name' });
    maxLength(path.name, 64, { message: 'Use at most 64 characters' });
  });
  protected readonly selected = signal<ReadonlySet<number>>(new Set());
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly friends = computed<User[]>(() =>
    this.store
      .channels()
      .filter((c) => c.type === 'DIRECT')
      .map((c) => c.members.find((m) => m.id === c.peerId)!)
      .sort((a, b) => a.displayName.localeCompare(b.displayName)),
  );
  protected readonly full = computed(() => this.selected().size + 1 >= MAX_GROUP_SIZE);

  protected toggle(id: number): void {
    this.selected.update((s) => {
      const next = new Set(s);
      if (!next.delete(id)) {
        next.add(id);
      }
      return next;
    });
  }

  protected create(event: Event): void {
    event.preventDefault();
    void submit(this.groupForm, {
      action: async () => {
        this.busy.set(true);
        this.error.set(null);
        try {
          const group = await this.api.createGroup(this.model().name.trim(), [...this.selected()]);
          await this.store.refreshChannels();
          this.store.select(group.channelId);
          this.modal().close();
        } catch (e) {
          this.error.set(errorMessage(e, 'The group could not be created.'));
        } finally {
          this.busy.set(false);
        }
      },
    });
  }
}
