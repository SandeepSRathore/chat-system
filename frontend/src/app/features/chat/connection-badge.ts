import { Component, computed, inject } from '@angular/core';
import { ChatSocketService } from '../../core/chat-socket.service';

/** Shows which stateful chat server holds this client's WebSocket, as picked by service discovery. */
@Component({
  selector: 'app-connection-badge',
  template: `
    <span class="badge" [class]="status()" role="status" [attr.title]="title()">
      <span class="signal" aria-hidden="true"></span>
      <span class="label">{{ label() }}</span>
    </span>
  `,
  styles: `
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 0.45rem;
      padding: 0.3rem 0.65rem;
      border: 1px solid var(--rule);
      border-radius: 999px;
      font-family: var(--font-mono);
      font-size: var(--step--1);
      color: var(--ink-muted);
      white-space: nowrap;
    }
    .signal {
      width: 0.5rem;
      height: 0.5rem;
      border-radius: 50%;
      background: var(--ink-muted);
    }
    .online .signal { background: var(--online); }
    .online { color: var(--ink); }
    .reconnecting .signal,
    .connecting .signal {
      background: var(--amber);
      animation: pulse 1.1s ease-in-out infinite;
    }
    .reconnecting { color: var(--amber); border-color: currentColor; }
    .replaced .signal { background: var(--danger); }
    @keyframes pulse {
      50% { opacity: 0.25; }
    }
  `,
})
export class ConnectionBadge {
  private readonly socket = inject(ChatSocketService);

  protected readonly status = this.socket.status;
  protected readonly label = computed(() => {
    switch (this.status()) {
      case 'online':
        return this.socket.serverId() ?? 'connected';
      case 'connecting':
        return 'connecting';
      case 'reconnecting':
        return 'reconnecting';
      case 'replaced':
        return 'open elsewhere';
      default:
        return 'offline';
    }
  });
  protected readonly title = computed(() => {
    switch (this.status()) {
      case 'online':
        return `Connected to chat server ${this.socket.serverId()}`;
      case 'reconnecting':
        return 'Connection lost. Finding another chat server; unsent messages will go out when it returns.';
      case 'replaced':
        return 'This account connected from another tab. Reload this page to use it here.';
      default:
        return 'Connecting to a chat server';
    }
  });
}
