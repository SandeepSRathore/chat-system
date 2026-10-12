import { Service, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
import { AuthService } from './auth.service';
import { backoffDelay } from './backoff';
import { PresenceServerFrame, PresenceStatus, SocketStatus } from './models';

/** Book: the client sends a heartbeat every 5s; it is marked offline after missing them for 30s. */
export const HEARTBEAT_INTERVAL_MS = 5_000;

/** WebSocket to a presence server (through the load balancer): heartbeats out, friends' status in. */
@Service()
export class PresenceSocketService {
  private readonly auth = inject(AuthService);

  readonly status = signal<SocketStatus>('offline');
  readonly presence = signal<ReadonlyMap<number, PresenceStatus>>(new Map());

  private readonly snapshotSubject = new Subject<void>();
  /** A snapshot arrives on connect and whenever the server sees our friend list change. */
  readonly snapshots$ = this.snapshotSubject.asObservable();

  private socket?: WebSocket;
  private attempts = 0;
  private stopped = true;
  private heartbeatTimer?: ReturnType<typeof setInterval>;
  private reconnectTimer?: ReturnType<typeof setTimeout>;

  start(): void {
    if (!this.stopped) {
      return;
    }
    this.stopped = false;
    this.open();
  }

  stop(): void {
    this.stopped = true;
    clearInterval(this.heartbeatTimer);
    clearTimeout(this.reconnectTimer);
    this.socket?.close(1000, 'Logged out');
    this.socket = undefined;
    this.status.set('offline');
    this.presence.set(new Map());
  }

  /** Merges statuses fetched on demand (group members) or pushed by the server. */
  merge(statuses: PresenceStatus[]): void {
    this.presence.update((current) => {
      const next = new Map(current);
      statuses.forEach((s) => next.set(s.userId, s));
      return next;
    });
  }

  private open(): void {
    const token = this.auth.token();
    if (this.stopped || !token) {
      return;
    }
    this.status.set(this.attempts === 0 ? 'connecting' : 'reconnecting');
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new WebSocket(`${scheme}://${location.host}/ws/presence?token=${encodeURIComponent(token)}`);
    this.socket = socket;
    socket.onopen = () => {
      this.attempts = 0;
      this.status.set('online');
      this.heartbeatTimer = setInterval(() => socket.send('{"type":"HEARTBEAT"}'), HEARTBEAT_INTERVAL_MS);
    };
    socket.onmessage = (event) => {
      const frame = JSON.parse(event.data as string) as PresenceServerFrame;
      if (frame.type === 'SNAPSHOT') {
        this.merge(frame.presences);
        this.snapshotSubject.next();
      } else {
        this.merge([frame.presence]);
      }
    };
    socket.onclose = () => {
      clearInterval(this.heartbeatTimer);
      if (this.socket !== socket || this.stopped) {
        return;
      }
      this.status.set('reconnecting');
      this.reconnectTimer = setTimeout(() => this.open(), backoffDelay(this.attempts++));
    };
  }
}
