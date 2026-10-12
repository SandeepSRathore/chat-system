import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { ChatStore } from '../../core/chat-store.service';
import { AddFriendDialog } from './add-friend-dialog';
import { ChannelList } from './channel-list';
import { Conversation } from './conversation';
import { CreateGroupDialog } from './create-group-dialog';

@Component({
  selector: 'app-chat-shell',
  imports: [ChannelList, Conversation, AddFriendDialog, CreateGroupDialog],
  host: { '[class.in-conversation]': 'store.activeId() !== null' },
  template: `
    <app-channel-list (addFriend)="dialog.set('friend')" (newGroup)="dialog.set('group')" (signOut)="signOut()" />
    <app-conversation />
    @switch (dialog()) {
      @case ('friend') {
        <app-add-friend-dialog (closed)="dialog.set(null)" />
      }
      @case ('group') {
        <app-create-group-dialog (closed)="dialog.set(null)" />
      }
    }
  `,
  styles: `
    :host {
      display: grid;
      grid-template-columns: minmax(16rem, 22rem) minmax(0, 1fr);
      height: 100dvh;
    }
    @media (max-width: 760px) {
      :host { grid-template-columns: 1fr; }
      :host(.in-conversation) app-channel-list,
      :host:not(.in-conversation) app-conversation { display: none; }
    }
  `,
})
export class ChatShell implements OnInit, OnDestroy {
  protected readonly store = inject(ChatStore);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly dialog = signal<'friend' | 'group' | null>(null);

  ngOnInit(): void {
    void this.store.init();
  }

  ngOnDestroy(): void {
    this.store.destroy();
  }

  protected signOut(): void {
    this.store.destroy();
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
