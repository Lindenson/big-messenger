-- V11__query_indexes.sql
-- Phase 8 — align indexes with the ACTUAL query predicates (grounded in the repository SQL), and
-- drop index dead-weight on the hottest table (message_history, one row per persisted message).
-- All changes are additive/idempotent-safe (CREATE IF NOT EXISTS / DROP IF EXISTS); no data change.
--
-- Rationale per index (query → file):
--
--  message_history — the hot aggregate. New composite (conversation_id, message_id) serves the four
--  hottest reads and makes two older indexes redundant:
--    * history-sync pagination  WHERE conversation_id=$1 AND message_id > $2 ORDER BY message_id LIMIT
--          (HistoryPostgresRepository.findByConversationSince) — index gives filter+order+limit, no sort;
--    * chat-list revival  NOT EXISTS(SELECT 1 FROM message_history WHERE conversation_id=c.id AND message_id > watermark)
--          and  max(message_id) WHERE conversation_id=$1  (ConversationPostgresAdapter) — backward index scan;
--    * moderation  SELECT frozen / DELETE ... WHERE conversation_id=$1 AND message_id=$2 (MessageModerationAdapter).
CREATE INDEX IF NOT EXISTS idx_mh_conversation_message ON message_history (conversation_id, message_id);

--  Standalone message_id — the composite above cannot serve message_id alone (not the leading column):
--    * DELETE FROM message_history WHERE message_id = ANY($1)   (history delete-by-id)
--    * UPDATE ... SET status='DELIVERED' WHERE message_id=$1 AND status='SENT'  (ReadReceiptsAdapter)
--  NOTE: kept NON-UNIQUE deliberately — a UNIQUE(message_id) is desirable (it matches the by-message_id
--  update/delete semantics and would enable ON CONFLICT idempotency) but is a behavioural change that must
--  be confirmed safe against the insert path (no client-retry double-insert). Tracked as a follow-up.
CREATE INDEX IF NOT EXISTS idx_mh_message_id ON message_history (message_id);

--  Retention sweeps scan by age within a freeze class (frozen has its own longer TTL, decision #6):
--    * DELETE FROM message_history WHERE created_at < $1 AND frozen = FALSE
--    * DELETE FROM message_history WHERE created_at < $1 AND frozen = TRUE   (HistoryPostgresRepository)
--  (frozen, created_at): equality on frozen, range on created_at — avoids a full seq scan on a huge table.
CREATE INDEX IF NOT EXISTS idx_mh_frozen_created ON message_history (frozen, created_at);

--  Redundant now — drop to cut write amplification on every insert:
--    * idx_mh_conversation_id (conversation_id) is a strict prefix of the new composite → covered by it.
--    * idx_mh_sender_recipient (sender_id, recipient_id) is used by NO query: the only sender/recipient
--      reads are single-column (sender_id=$1) or the OR form (sender_id=$1 OR recipient_id=$1), which the
--      optimizer serves by BitmapOr-ing idx_mh_sender_id + idx_mh_recipient_id (both KEPT). No query
--      filters sender AND recipient together, so this composite only cost inserts.
DROP INDEX IF EXISTS idx_mh_conversation_id;
DROP INDEX IF EXISTS idx_mh_sender_recipient;

--  outbox — the delete-after-deliver / ack path deletes by message_id but had no index for it:
--    * DELETE FROM outbox WHERE message_id = ANY($1)   (OutboxPostgresRepository)
--  (The claim CTE  WHERE lease_until <= now() ORDER BY id ... FOR UPDATE SKIP LOCKED  is already served
--   by idx_outbox_id_lease (lease_until, id); the id<$1 watermark GC uses the PK — both left as-is.)
CREATE INDEX IF NOT EXISTS idx_outbox_message_id ON outbox (message_id);
