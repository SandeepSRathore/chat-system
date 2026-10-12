export interface User {
  id: number;
  username: string;
  displayName: string;
}

export interface Session {
  token: string;
  user: User;
}

/** Mirrors com.chatsystem.common.wire.ChatMessage; createdAt is epoch millis. */
export interface ChatMessage {
  channelId: string;
  messageId: number;
  senderId: number;
  content: string;
  createdAt: number;
}

export interface Channel {
  id: string;
  type: 'DIRECT' | 'GROUP';
  name: string;
  peerId: number | null;
  groupId: number | null;
  ownerId: number | null;
  members: User[];
  lastMessage: ChatMessage | null;
}

export interface FriendRequests {
  incoming: User[];
  outgoing: User[];
}

export interface FriendRequestResult {
  status: 'PENDING' | 'ACCEPTED';
  user: User;
}

export interface Group {
  id: number;
  channelId: string;
  name: string;
  ownerId: number;
  members: User[];
}

export interface ChatServer {
  serverId: string;
  wsUrl: string;
  connections: number;
}

export interface PresenceStatus {
  userId: number;
  online: boolean;
  lastActiveAt: number;
}

export type ChatServerFrame =
  | { type: 'CONNECTED'; serverId: string; userId: number }
  | { type: 'ACK'; clientMsgId: string; message: ChatMessage }
  | { type: 'MESSAGE'; message: ChatMessage }
  | { type: 'ERROR'; clientMsgId: string | null; error: string }
  | { type: 'PONG' };

export type PresenceServerFrame =
  | { type: 'SNAPSHOT'; presences: PresenceStatus[] }
  | { type: 'PRESENCE'; presence: PresenceStatus };

/** What the author of a message sees about its journey. */
export type DeliveryState = 'sending' | 'sent' | 'failed' | 'received';

export interface ChatItem {
  /** Stable identity for rendering: clientMsgId for my sends, otherwise the message id. */
  key: string;
  channelId: string;
  clientMsgId?: string;
  messageId?: number;
  senderId: number;
  content: string;
  createdAt: number;
  state: DeliveryState;
  error?: string;
}

export type SocketStatus = 'offline' | 'connecting' | 'online' | 'reconnecting' | 'replaced';

export const MAX_GROUP_SIZE = 100;
export const MAX_MESSAGE_LENGTH = 4000;
