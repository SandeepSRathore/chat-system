import { Service, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
import { ApiService } from './api.service';
import { AuthService } from './auth.service';
import { backoffDelay } from './backoff';
import { ChatServerFrame, SocketStatus } from './models';

const PING_INTERVAL_MS = 25_000;
const REPLACED_CLOSE_CODE = 4001;

/**
 * The stateful WebSocket to a chat server. The server is chosen by service discovery on every
 * (re)connect, so if a chat server dies the client lands on a healthy one.
 */
@Service()
export class ChatSocketService {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);

  readonly status = signal<SocketStatus>('offline');
  readonly serverId = signal<string | null>(null);

  private readonly frameSubject = new Subject<ChatServerFrame>();
  private readonly connectedSubject = new Subject<void>();
  /** Every frame pushed by the chat server. */
  readonly frames$ = this.frameSubject.asObservable();
  /** Fires after each successful (re)connect so state can be resynchronised. */
  readonly connected$ = this.connectedSubject.asObservable();

  private socket?: WebSocket;
  private attempts = 0;
  private stopped = true;
  private reconnectTimer?: ReturnType<typeof setTimeout>;
  private pingTimer?: ReturnType<typeof setInterval>;

  start(): void {
    if (!this.stopped) {
      return;
    }
    this.stopped = false;
    void this.open();
  }

  stop(): void {
    this.stopped = true;
    clearTimeout(this.reconnectTimer);
    clearInterval(this.pingTimer);
    this.socket?.close(1000, 'Logged out');
    this.socket = undefined;
    this.status.set('offline');
    this.serverId.set(null);
  }

  /** Returns false when the socket is not open; the caller keeps the frame for a later resend. */
  send(frame: object): boolean {
    if (this.socket?.readyState !== WebSocket.OPEN) {
      return false;
    }
    this.socket.send(JSON.stringify(frame));
    return true;
  }

  private async open(): Promise<void> {
    const token = this.auth.token();
    if (this.stopped || !token) {
      return;
    }
    this.status.set(this.attempts === 0 ? 'connecting' : 'reconnecting');
    try {
      const server = await this.api.chatServer();
      if (this.stopped) {
        return;
      }
      const socket = new WebSocket(`${server.wsUrl}?token=${encodeURIComponent(token)}`);
      this.socket = socket;
      socket.onmessage = (event) => this.onFrame(JSON.parse(event.data as string) as ChatServerFrame);
      socket.onopen = () => {
        this.attempts = 0;
        this.status.set('online');
        this.pingTimer = setInterval(() => this.send({ type: 'PING' }), PING_INTERVAL_MS);
      };
      socket.onclose = (event) => {
        clearInterval(this.pingTimer);
        if (this.socket !== socket) {
          return;
        }
        this.socket = undefined;
        this.serverId.set(null);
        if (event.code === REPLACED_CLOSE_CODE) {
          // Another tab took over this account's single session.
          this.stopped = true;
          this.status.set('replaced');
          return;
        }
        this.scheduleReconnect();
      };
    } catch {
      this.scheduleReconnect();
    }
  }

  private onFrame(frame: ChatServerFrame): void {
    if (frame.type === 'CONNECTED') {
      this.serverId.set(frame.serverId);
      this.connectedSubject.next();
    }
    this.frameSubject.next(frame);
  }

  private scheduleReconnect(): void {
    if (this.stopped) {
      return;
    }
    this.status.set('reconnecting');
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = setTimeout(() => void this.open(), backoffDelay(this.attempts++));
  }
}
