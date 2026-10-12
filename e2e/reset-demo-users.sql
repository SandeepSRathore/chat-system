-- Removes the walkthrough users (maya, leo, sam) and everything they created, so screenshots.mjs can run again.
-- docker exec -i chat-system-postgres-1 psql -U chat < reset-demo-users.sql
CREATE TEMP TABLE demo AS SELECT id FROM users WHERE username IN ('maya', 'leo', 'sam');
DELETE FROM chat_groups WHERE owner_id IN (SELECT id FROM demo);
DELETE FROM group_members WHERE user_id IN (SELECT id FROM demo);
DELETE FROM friend_requests WHERE requester_id IN (SELECT id FROM demo) OR addressee_id IN (SELECT id FROM demo);
DELETE FROM friendships WHERE user_id IN (SELECT id FROM demo) OR friend_id IN (SELECT id FROM demo);
DELETE FROM users WHERE id IN (SELECT id FROM demo);
