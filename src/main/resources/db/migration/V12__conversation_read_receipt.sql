-- V12: per-participant READ RECEIPT on the conversation — the read-up-to messageId (ULID cursor)
-- for each side. Replaces per-message message_history.status='READ' writes (removed) with a single,
-- coalesced, write-behind watermark per side: on READ_IN the server advances the reader's column to the
-- client-supplied lastReadId (monotonic, via GREATEST). NULL = that side has read nothing yet.
--
-- Read path is join-free: the guarded history / GET /chats path already loads the conversation row, so
-- the peer's receipt (peerLastReadId) is returned straight from the row. ✓✓ ⇔ myMessage.id <= peerLastReadId.
ALTER TABLE conversation
    ADD COLUMN client_read_receipt text,
    ADD COLUMN master_read_receipt text;
