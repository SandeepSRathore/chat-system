import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Service, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Channel, ChatMessage, ChatServer, FriendRequestResult, FriendRequests, Group, PresenceStatus, User } from './models';

/** Typed access to api-service (and the presence lookup routed by the load balancer). */
@Service()
export class ApiService {
  private readonly http = inject(HttpClient);

  channels(): Promise<Channel[]> {
    return firstValueFrom(this.http.get<Channel[]>('/api/v1/channels'));
  }

  history(channelId: string, before?: number, limit = 50): Promise<ChatMessage[]> {
    const params: Record<string, string> = { limit: String(limit) };
    if (before !== undefined) {
      params['before'] = String(before);
    }
    return firstValueFrom(
      this.http.get<ChatMessage[]>(`/api/v1/channels/${encodeURIComponent(channelId)}/messages`, { params }),
    );
  }

  searchUsers(query: string): Promise<User[]> {
    return firstValueFrom(this.http.get<User[]>('/api/v1/users/search', { params: { q: query } }));
  }

  friendRequests(): Promise<FriendRequests> {
    return firstValueFrom(this.http.get<FriendRequests>('/api/v1/friends/requests'));
  }

  /** Becomes a friendship right away only if the other user had already asked us. */
  sendFriendRequest(userId: number): Promise<FriendRequestResult> {
    return firstValueFrom(this.http.post<FriendRequestResult>(`/api/v1/friends/requests/${userId}`, null));
  }

  acceptFriendRequest(userId: number): Promise<User> {
    return firstValueFrom(this.http.post<User>(`/api/v1/friends/requests/${userId}/accept`, null));
  }

  /** Declines an incoming request or withdraws an outgoing one. */
  declineFriendRequest(userId: number): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/v1/friends/requests/${userId}`));
  }

  createGroup(name: string, memberIds: number[]): Promise<Group> {
    return firstValueFrom(this.http.post<Group>('/api/v1/groups', { name, memberIds }));
  }

  leaveGroup(groupId: number, userId: number): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/v1/groups/${groupId}/members/${userId}`));
  }

  /** Service discovery: which chat server this client should connect to. */
  chatServer(): Promise<ChatServer> {
    return firstValueFrom(this.http.get<ChatServer>('/api/v1/chat/server'));
  }

  presence(userIds: number[]): Promise<PresenceStatus[]> {
    return firstValueFrom(
      this.http.get<PresenceStatus[]>('/api/v1/presence', { params: { userIds: userIds.join(',') } }),
    );
  }
}

/** The human-readable reason from a ProblemDetail response, or a fallback. */
export function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof HttpErrorResponse) {
    const detail = (error.error as { detail?: string } | null)?.detail;
    if (detail) {
      return detail;
    }
    if (error.status === 0) {
      return 'The server cannot be reached. Check that the backend is running.';
    }
  }
  return fallback;
}
