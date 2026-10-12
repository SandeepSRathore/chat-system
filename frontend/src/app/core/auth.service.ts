import { HttpClient } from '@angular/common/http';
import { Service, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './models';

const STORAGE_KEY = 'chat.session';

function readStoredSession(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

@Service()
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly session = signal<Session | null>(readStoredSession());

  readonly user = computed(() => this.session()?.user ?? null);
  readonly token = computed(() => this.session()?.token ?? null);
  readonly isLoggedIn = computed(() => this.session() !== null);

  async login(username: string, password: string): Promise<void> {
    this.store(await firstValueFrom(this.http.post<Session>('/api/v1/auth/login', { username, password })));
  }

  async register(username: string, displayName: string, password: string): Promise<void> {
    this.store(
      await firstValueFrom(this.http.post<Session>('/api/v1/auth/register', { username, displayName, password })),
    );
  }

  /** Marks the user offline right away (otherwise presence waits for the heartbeat TTL). */
  logout(): void {
    const token = this.token();
    if (token) {
      void fetch('/api/v1/presence/offline', {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
        keepalive: true,
      }).catch(() => undefined);
    }
    this.clear();
  }

  clear(): void {
    this.session.set(null);
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // storage unavailable: the in-memory session is already cleared
    }
  }

  private store(session: Session): void {
    this.session.set(session);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    } catch {
      // storage unavailable: stay logged in for this tab only
    }
  }
}
