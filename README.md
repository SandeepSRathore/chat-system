# Chat system: Alex Xu, *System Design Interview*, Chapter 12

A working implementation of the book's chat system: 1:1 chat, group chat (up to 100 members) and online presence.
The backend is Spring Boot 4 / Java 25 microservices and the frontend is Angular 22.

**Read [`docs/DESIGN.md`](docs/DESIGN.md) first.** It covers requirements, estimates, diagrams, the data model, message flows and how the code deviates from the book.

```
browser ──HTTP──▶ nginx ──▶ api-service ×2 ──▶ PostgreSQL · Cassandra · Redis · ZooKeeper
   │      └─WS /ws/presence──▶ presence-service ×2 ──▶ Redis (TTL keys + pub/sub)
   └──WS (direct, picked by discovery)──▶ chat-service chat-1 / chat-2 ──▶ Kafka ──▶ Cassandra + Redis pub/sub
```

| Module | Role in the book |
|---|---|
| `backend/api-service` | Stateless API servers: auth (JWT), users, friends, groups, history, service discovery |
| `backend/chat-service` | Stateful chat servers: WebSocket, per-channel message IDs, message sync queue (Kafka), persistence and routing |
| `backend/presence-service` | Presence servers: heartbeats, TTL-based offline detection, fan-out to friends |
| `backend/chat-common` | Shared wire types, Redis keys and JWT verification |
| `frontend` | Angular client. The header badge shows which chat server you're connected to. |
| `infra` | docker-compose, nginx (load balancer), Cassandra schema, Dockerfiles |

## Run everything in Docker

```bash
cd infra
echo "CHAT_JWT_SECRET=$(openssl rand -base64 48)" > .env    # once; .env is gitignored
docker compose --profile app up -d --build
open http://localhost:8000
```

No signing secret is committed. Every service refuses to start without `CHAT_JWT_SECRET`, and all of them must share the same one.

The stack runs 6 JVMs plus Cassandra and Kafka, so give Docker Desktop **at least 4 GB of memory** (6 GB is comfortable).
The first start takes a few minutes while Cassandra boots and the images build.

### Try the book's scenarios
1. Register two users in two browsers (or one normal window and one private window). The second user usually lands on the other chat server; check the badge in the header.
2. Send a friend request; the other user sees it in the sidebar and accepts it (no chatting or presence until they do). Then chat: messages cross servers via Kafka (`message.sync`) and Redis pub/sub (`chat.deliver.{server}`).
3. Create a group and send messages. Members with the group closed get an unread badge.
4. Close one browser **without signing out**. About 30 s later the other user sees "Last seen …": the heartbeat key expired. Signing out marks the user offline immediately.
5. Run `docker stop chat-1`. Clients on chat-1 reconnect, discovery sends them to chat-2, and unsent messages go out once they're connected. Run `docker start chat-1` to bring it back.

## Develop locally

```bash
cd infra && docker compose up -d                 # infrastructure only

export CHAT_JWT_SECRET=$(openssl rand -base64 48)   # same value for every service below
cd backend && mvn install -DskipTests
java -jar api-service/target/api-service-0.1.0-SNAPSHOT.jar
java -jar chat-service/target/chat-service-0.1.0-SNAPSHOT.jar           # chat-1 on :8091
SERVER_PORT=8092 CHAT_SERVER_ID=chat-2 CHAT_PUBLIC_WS_URL=ws://localhost:8092/ws/chat \
  java -jar chat-service/target/chat-service-0.1.0-SNAPSHOT.jar         # chat-2 on :8092
java -jar presence-service/target/presence-service-0.1.0-SNAPSHOT.jar

cd frontend && npm install && npx ng serve       # http://localhost:4200 (proxies /api and /ws/presence)
```

Stop the Docker `app` profile first; it uses ports 8091 and 8092.

## Tests

```bash
cd backend && mvn verify       # unit + Testcontainers integration tests (Docker required)
cd frontend && npx ng test --watch=false
```

- **chat-common:** channel IDs and JWT handling.
- **api-service** (Postgres, Redis, Cassandra, embedded ZooKeeper):
  - a friend request grants nothing until accepted; crossing requests become a friendship; requests can be declined
  - friendships are mirrored to Redis
  - the group cap is enforced
  - history access is checked
  - discovery picks the least-loaded chat server
- **chat-service** (Kafka, Redis, Cassandra):
  - a send over WebSocket is acknowledged, then delivered to the other user and persisted
  - a retried send is idempotent
  - non-members are rejected
  - the handshake requires a valid token
- **presence-service** (Redis): the first heartbeat marks a user ONLINE; missed heartbeats make the key expire and mark them OFFLINE; logout is immediate.
- **frontend:** store behaviour, meaning outbox/ACK/failed/retry, ordering and dedupe, unread counts, accepting friend requests, and on-demand group presence.

## Configuration

| Variable | Service | Default |
|---|---|---|
| `CHAT_JWT_SECRET` | all | **required**, no default (≥ 32 bytes, e.g. `openssl rand -base64 48`) |
| `CHAT_SERVER_ID`, `CHAT_PUBLIC_WS_URL` | chat | `chat-1`, `ws://localhost:8091/ws/chat` |
| `CHAT_PRESENCE_TTL` | presence | `30s` (clients heartbeat every 5 s) |
| `CHAT_MAX_GROUP_SIZE` | api | `100` |
| `CHAT_ZOOKEEPER_CONNECT` | api, chat | `localhost:2181` |
