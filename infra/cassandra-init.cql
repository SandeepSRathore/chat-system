CREATE KEYSPACE IF NOT EXISTS chat
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

-- One partition per channel (dm:{a}:{b} or g:{groupId}), newest message first.
CREATE TABLE IF NOT EXISTS chat.messages_by_channel (
  channel_id text,
  message_id bigint,
  sender_id  bigint,
  content    text,
  created_at timestamp,
  PRIMARY KEY ((channel_id), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);
