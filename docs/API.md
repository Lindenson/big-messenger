# API reference — WebSocket, REST & Kafka

The protocol contract. For *how a client must behave* (ACKs, dedup, ping/pong, reconnect), see
[CLIENT-CONTRACT](CLIENT-CONTRACT.md).

- [WebSocket protocol](#websocket-protocol)
- [REST — /api/chats](#rest--apichats)
- [REST — auxiliary](#rest--auxiliary)
- [REST — admin console](#rest--admin-console)
- [Kafka — order.events (inbound)](#kafka--orderevents-inbound)

---

## WebSocket protocol

Connect to `ws://<host>/ws` with the **Ory identity headers on the handshake** (same as REST). The session's
`X-User-Id` is the authenticated sender; every frame is a JSON `Message`.

**Frame schema** (fields used vary by `type`):

| field | set by | meaning |
|---|---|---|
| `type` | both | the `MessageType` (below) |
| `senderId` / `recipientId` | client (in) / server (out) | identities — **server overwrites inbound** (anti-spoof) |
| `conversationId` | both | chat id (the `(client,master)` pair); `system:<id>` for notices |
| `messageId` | client on send → **server reassigns a ULID** | monotonic; also the history page cursor |
| `correlationId` | both | links an ACK/receipt to the referenced `messageId` |
| `ackId` | client on `*_ACK` | the outbox row id being acked (advances the GC watermark) |
| `payload.kind` / `payload.body` | both | `text`·`custom`·`event`·`attachment`; `body` = content/reference |
| `meta` | both | opaque string map (`orderId`, attachment `objectKey/fileName/...`) |
| `senderTimestamp` / `senderTimezone` | client | epoch ms + IANA tz (validated, bounded skew) |
| `serverTimestamp` / `id` / `sequenceNumber` | server | server-assigned |

**Inbound — client → server** (only these accepted; anything else rejected):

| `type` | purpose |
|---|---|
| `CHAT_IN` | send a chat message (persistent, Strategy A) — membership + block guarded |
| `SIGNAL_IN` | WebRTC offer/answer/ICE (Strategy S, ephemeral) |
| `TYPING_IN` | typing indicator (Strategy S) — delivered live as `TYPING_OUT`, never persisted |
| `CHAT_ACK` | delivery ACK: `correlationId`=delivered `messageId`, `ackId`=outbox id → `SENT→DELIVERED` |
| `READ_IN` | recipient read → marks `READ` + pushes `READ_OUT` to the sender |
| `SYSTEM_ACK` | confirm a system notice → retracts the dead-letter draft |

**Outbound — server → client:**

| `type` | purpose |
|---|---|
| `CHAT_OUT` | a delivered chat message |
| `CHAT_ACK` | `SENT` receipt back to the sender |
| `SIGNAL_OUT` | a delivered signaling frame |
| `TYPING_OUT` | peer is typing (transient) |
| `READ_OUT` | "your messages were read" → the original sender |
| `PRESENT_INIT` / `PRESENT_JOIN` / `PRESENT_LEAVE` | presence snapshot / peer online / peer offline |
| `SYSTEM_OUT` | a must-arrive system notice (Strategy C) — confirm with `SYSTEM_ACK` |
| `SERVICE_OUT` | transient service notice (e.g. ingress overload / backpressure) |

## REST — /api/chats

| Method & path | Purpose | Notable |
|---|---|---|
| `POST /api/chats` | Create/return chat `{clientId, masterId, metadata}` (idempotent). **ADMIN/SERVICE only** | `201`/`200`/`403` |
| `GET /api/chats` | List caller's chats — recent-first, **includes blocked**, excludes caller's deleted (until revived) | `200` |
| `GET /api/chats/{id}/messages?since=&limit=&includeDeleted=` | Cursor-paginated history (membership-gated), floored at the caller's delete watermark unless `includeDeleted=true` | `200`/`403`/`404` |
| `DELETE /api/chats/{id}` | Delete the chat **for the caller** (watermark; reversible by new activity) | `204` |
| `POST /api/chats/{id}/block` · `DELETE …/block` | Block / unblock the peer (mutual; the only terminal messaging stop) | `204` |
| `DELETE /api/chats/{id}/messages/{messageId}` | Delete a message (only if not frozen) | `204`/`404`/`409` frozen |
| `POST /api/chats/{id}/freeze` `{orderId}` | Freeze that order's messages (one-way) | `200 {frozen:n}`/`400` |
| `POST /api/chats/{id}/read` | Recipient marks READ (reconnect/bulk fallback; primary is WS `READ_IN`) | `200 {read:n}` |
| `GET /api/chats/{id}/receipts` | Per-message status (`SENT`/`DELIVERED`/`READ`) | `200` |
| `POST /api/chats/{id}/attachments/upload-url` · `…/{aid}/confirm` · `GET …/{aid}/download-url` | Two-phase presigned attachment upload (ADR-010) | see [CLIENT-CONTRACT](CLIENT-CONTRACT.md) |

## REST — auxiliary

| Method & path | Purpose |
|---|---|
| `GET /api/history` | Caller-scoped message history |
| `GET /api/presence` | Presence snapshot |
| `POST /api/system/notify` | Emit a must-arrive system notice (Strategy C) — **ADMIN/SERVICE only** |
| `GET /q/health`, `GET /q/metrics` | Health & Prometheus metrics |
| `GET /q/openapi`, `GET /q/swagger-ui` | OpenAPI spec + Swagger UI |

## REST — admin console

`/api/admin/chats` — **ADMIN only** (a non-ADMIN, incl. `SERVICE`, gets `403`). Platform-wide reads, **not**
membership-gated or per-side filtered — an admin sees every chat regardless of who deleted or blocked it.

| Method & path | Purpose |
|---|---|
| `GET /api/admin/chats?participant=&conversationId=&blocked=&from=&to=&sort=&limit=&offset=` | List all chats with filters (`sort` ∈ `created_asc\|created_desc\|updated_asc\|updated_desc`, `limit` ≤ 200). Returns `{items, total, limit, offset}` |
| `GET /api/admin/chats/stats` | Platform counts `{total, blocked}` |

## Kafka — order.events (inbound)

The service **consumes** `order.events` (channel `order-events-in`, SmallRye Kafka) — a second driving
adapter on the same create-chat / freeze core ops (ADR-007). JSON envelope:

```jsonc
{
  "eventId":    "uuid",                 // producer dedup key (consumer is idempotent)
  "eventType":  "order.master.interested",
  "occurredAt": "2026-06-24T10:00:00Z",
  "payload": { "clientId": "...", "masterId": "...", "orderId": "..." }
}
```

| `eventType` (config-driven) | action |
|---|---|
| `order.master.interested` (`messenger.order-events.type.master-interested`) | **UC-H01** create the `(client,master)` chat (idempotent, `orderId` as metadata) |
| `order.contract.concluded` (`messenger.order-events.type.contract-reached`) | **UC-H04** resolve the chat by pair → freeze that order's messages |

> **Assumed contract — reconcile with the Order team.** The `eventType` strings are config
> (`messenger.order-events.type.*`), so reconciliation is config, not code.
