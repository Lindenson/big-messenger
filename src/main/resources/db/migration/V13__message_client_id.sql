-- Client-original message id (the id the phone generated for a CHAT_IN). The server reassigns a ULID as
-- the canonical message_id, but the phone only ever knows its own id (the server id is echoed on the ACK,
-- but a just-sent / delivered message may be deleted before/without that). Storing it makes a message
-- addressable by EITHER id, so delete-by-original-id works when the phone lacks the server id.
-- Sourced from the message's correlationId at persist time (CHAT_IN → client messageId).
ALTER TABLE message_history ADD COLUMN client_message_id varchar(128);

-- Delete/lookup by the client-original id is scoped to a conversation; index the pair.
CREATE INDEX IF NOT EXISTS idx_mh_conversation_client_msg
    ON message_history (conversation_id, client_message_id);
