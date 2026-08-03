# Client integration contract

Everything a frontend/client must honour to talk to Hormigas Messenger correctly. The server is
deliberately **best-effort on ordering and de-duplication** and **authoritative on durability and
authorization** — the client owns the other half of the contract below.

- [Identity & security headers](#identity--security-headers)
- [Transport: REST vs WebSocket](#transport-rest-vs-websocket)
- [Connect / reconnect: always re-read history](#connect--reconnect-always-re-read-history)
- [Acknowledgements you must send](#acknowledgements-you-must-send)
- [Read receipts (✓✓)](#read-receipts-)
- [Ordering & de-duplication are the client's job](#ordering--de-duplication-are-the-clients-job)
- [Liveness: mandatory ping/pong](#liveness-mandatory-pingpong)
- [One session per user](#one-session-per-user)
- [Flow control: credits & rate limits](#flow-control-credits--rate-limits)
- [Message types you may send (and may not)](#message-types-you-may-send-and-may-not)
- [Timestamps & timezone](#timestamps--timezone)
- [Metadata](#metadata)
- [Presence & participant lists](#presence--participant-lists)
- [Delete, freeze & retention](#delete-freeze--retention)

---

## Identity & security headers

The service runs **behind the Ory edge (Kratos + Oathkeeper)** and trusts the identity headers the edge
injects — it does **not** validate JWTs itself. The client never sets these directly in production; the
edge authenticates the Kratos session and stamps them:

| Header | Meaning |
|---|---|
| `X-User-Id` | caller identity (**required**; missing/blank → WS close / HTTP `401`) |
| `X-User` | display name |
| `X-Role` | `MASTER` / `CLIENT` (`ADMIN`/`SERVICE` for platform callers) |
| `X-User-Email` | email |

The **same headers are required on the WebSocket handshake** as on REST. The server treats the session's
`X-User-Id` as the authenticated sender and **overwrites** any `senderId`/`recipientId` a client puts in a
frame (anti-impersonation) — you cannot post as someone else or deliver to a non-participant.

> ⚠️ The service must **only** be reachable through the Ory edge. A directly-exposed port lets anyone forge
> `X-Role: ADMIN`. See [DEPLOYMENT](DEPLOYMENT.md).

## Transport: REST vs WebSocket

| Use REST for | Use WebSocket for |
|---|---|
| history sync, chat list, receipts, freeze, block/unblock, delete, attachments, read-fallback | live send/receive, delivery & read ACKs, typing, signaling, presence events |

Both share the identity model. A message sent over WS is durably persisted **before** it is ACKed; a
message you fetch over REST is the durable source of truth.

## Message structure

Every WebSocket frame is a single JSON `Message`. A minimal inbound chat message the client sends:

```json
{
  "type": "CHAT_IN",
  "conversationId": "01JABCDE...",
  "messageId": "client-local-uuid",
  "recipientId": "u-master-42",
  "senderTimestamp": 1751540000000,
  "senderTimezone": "Europe/Madrid",
  "payload": { "kind": "text", "body": "When can you start?" },
  "meta": { "orderId": "ord-991" }
}
```

| field | set by | meaning |
|---|---|---|
| `type` | client | one of the inbound `MessageType`s (below) |
| `conversationId` | client | the chat = the `(client, master)` pair |
| `messageId` | client → **server reassigns a ULID** | your id returns as `correlationId`; the server id is monotonic + the history cursor |
| `senderId` / `recipientId` | **server overwrites** `senderId`; client sets `recipientId` | anti-impersonation — you can't post as/deliver to anyone else |
| `payload.kind` / `payload.body` | client | `text` · `custom` · `event` · `attachment`; `body` = content/reference |
| `meta` | client | opaque string map (`orderId`, …); attachments' meta is server-filled |
| `senderTimestamp` / `senderTimezone` | client | epoch ms + IANA tz (validated) |
| `correlationId` / `ackId` | client on ACKs | link an ACK/receipt to a message / the outbox row id; on `READ_IN` = the last-read server `messageId` |
| `serverMessageId` | **server, on `CHAT_ACK`** | the server ULID of *your* sent message — capture it for ✓✓ + delete-by-server-id |
| `serverTimestamp` / `id` / `sequenceNumber` | server | server-assigned |

Outbound frames carry the same shape with the server-assigned fields populated. The **full per-type field
usage and every `MessageType`** are in the [API reference](API.md).

## Connect / reconnect: always re-read history

**On every WS (re)connect the client MUST pull conversation history over REST, then resume the live
socket.** That read-through is what guarantees nothing is lost across a disconnect gap.

```
GET /api/chats/{id}/messages?limit=<n>                     # chat-open: the LATEST n (newest), returned oldest-first
GET /api/chats/{id}/messages?before=<oldest id>&limit=<n>  # scroll-up: the next-older n (message_id < before)
GET /api/chats/{id}/messages?since=<last id>&limit=<n>     # reconnect catch-up: messages NEWER than <last id>
```

The cursor is the server `messageId` (ULID). The response is `{ "messages": [...oldest-first...],
"peerLastReadId": "<ULID|null>" }` — `messages` is the page, `peerLastReadId` is the peer's read watermark
(see [Read receipts](#read-receipts-)). `?includeDeleted=true` returns your pre-delete-watermark history too.

Live frames that arrive during/after the sync may duplicate REST rows — that is expected; you
de-duplicate (below). No message is lost across the gap; duplicates are possible by design.

## Acknowledgements you must send

Delivery status advances **only** on client ACKs — a WS push is best-effort, never assumed delivered:

| You received | You must send | Effect |
|---|---|---|
| `CHAT_OUT` (a delivered chat message) | `CHAT_ACK` with `correlationId` = the message's server `messageId`, `ackId` = its outbox id | `SENT → DELIVERED`; **advances the GC watermark** (lets the server reclaim the outbox row) |
| read the conversation up to a message | `READ_IN` with `correlationId` = the highest **server `messageId`** you've displayed (or `POST /api/chats/{id}/read?lastReadId=<serverMessageId>` on reconnect/bulk) | advances your **read watermark**; the sender sees it as `peerLastReadId` and gets a live `READ_OUT` — see [Read receipts](#read-receipts-) |
| `SYSTEM_OUT` (must-arrive notice) | `SYSTEM_ACK` with `correlationId` = the notice `messageId` | retracts the dead-letter draft (no-loss, no-duplicate) |

> **Your own SENT receipt:** the server's `CHAT_ACK` to *you* (the sender) carries `correlationId` = your
> local `messageId` (match your send) **and** `serverMessageId` = the server-assigned ULID. **Capture
> `serverMessageId`** — it is the canonical id you need for ✓✓ (read receipts) and delete-by-server-id.

**Not ACKing has consequences:** an un-ACKed message keeps its outbox row (redelivered on reconnect), and
a recipient that accumulates too large an un-ACKed backlog (`processing.session.max-pending`) is
**force-disconnected** by the overload reaper. ACK promptly.

## Read receipts (✓✓)

Read state is a **per-participant watermark on the conversation**, not a per-message flag. Each side has a
read receipt = the **server `messageId` (a ULID)** it has read up to; it only ever moves **forward**
(monotonic — an older id never regresses it). Everything here lives in the **server-ULID space**, never your
local ids.

- **Mark read (you → server).** Send `READ_IN` over WS with `correlationId` = the **highest server
  `messageId` you have displayed** (the ULID from `CHAT_OUT.messageId`, or from history). On reconnect / bulk,
  use `POST /api/chats/{id}/read?lastReadId=<serverMessageId>`. ⚠️ It MUST be the **server** `messageId` — not
  your local/client id — or it won't line up with the sender's ✓✓.
- **Render ✓✓ (the peer's progress).** `GET /api/chats/{id}/messages` returns **`peerLastReadId`** (the other
  side's watermark). A message **you sent** is read (✓✓) ⇔ `yourMessage.serverMessageId <= peerLastReadId`
  (ULID string comparison). You get your sent messages' `serverMessageId` from the `CHAT_ACK` above (capture it).
- **Live update.** While connected you also receive `READ_OUT` with `correlationId` = the peer's new
  read-up-to id — update ✓✓ live, no history re-fetch needed.
- **Semantics.** Soft / best-effort (may lag a beat; self-heals on the next `READ_IN`), monotonic. It is NOT a
  delivery guarantee — that is `CHAT_ACK` + the outbox.

## Ordering & de-duplication are the client's job

- **De-dup by `messageId`.** Delivery is **at-least-once**; the server does best-effort suppression only.
  Rare wire duplicates are expected — the client is the authoritative de-duplicator.
- **Final sort by the server `messageId`** (a **ULID**, time-monotonic). The server assigns the canonical
  `messageId` on inbound; your own id travels back as `correlationId` for ACK-matching and dedup. Server
  ordering is **best-effort**; the client re-sorts by the monotonic id for display.

## Liveness: mandatory ping/pong

The server **auto-pings every 10 s**; a live client MUST reply `pong`. A connection that stops responding
past `processing.session.idle-timeout-ms` (default **35 s**) is **force-closed** by the liveness reaper
(→ presence + watermark cleanup). Keep the socket answering pings or you will be disconnected.

## One session per user

**Double sessions are not allowed.** A new WS connection for a user performs a **clean takeover** — the
previous session is evicted. Multi-device fan-out is out of scope for v1 (per-user ACK/watermark/ordering).
Design the client for a single active socket per identity.

## Flow control: credits & rate limits

Two independent guards protect the service; both surface to the client:

- **Per-connection credits (WebSocket).** Each socket has a token bucket (burst + refill/s). Exceeding it
  rejects the frame at ingress; the sender should back off and retry. This is the per-connection
  flood/abuse guard — sustained per-connection rate ≈ the refill rate.
- **REST rate limit (per caller × endpoint group).** Every `/api/*` endpoint is token-bucket limited; over
  the limit → **`429`**. Attachments are the strictest group. Back off on `429`. (`/q/*` health/metrics
  are exempt.)

See [CONFIGURATION](CONFIGURATION.md) for the exact defaults.

## Message types you may send (and may not)

The frame `type` (a `MessageType`) determines the whole lifecycle and persistence — **you choose it, and it
decides durability**. Each kind maps to one of four **handling strategies** (orthogonal properties resolved
per type):

| Strategy | Durably persisted | Retried until ACK | Idempotent | Kept in History | Used for |
|---|:---:|:---:|:---:|:---:|---|
| **A — persistent** | yes | yes | yes | yes (own longer TTL when frozen) | chat messages |
| **B — fire-and-forget** | no | no | no | no | typing, presence |
| **C — retry-then-purge** | transient (outbox) | yes | yes | no | must-arrive system notices |
| **S — signaling** | no (transient) | yes | yes | no | WebRTC call setup |

So a `CHAT_IN` is durable and retried (Strategy A); a `TYPING_IN` is best-effort and never stored (B); a
system notice is held-until-ACK then purged (C); signaling is transient but delivered (S). **The type you
send is the contract for how long it lives and whether it survives a restart.**

**Inbound — client → server (only these are accepted; anything else is rejected):**

| `type` | meaning | persisted? |
|---|---|---|
| `CHAT_IN` | a chat message (Strategy A) — membership + block guarded | **yes** (History + Outbox) |
| `SIGNAL_IN` | WebRTC offer/answer/ICE (Strategy S) | no (transient) |
| `TYPING_IN` | typing indicator (Strategy S) | no (delivered live as `TYPING_OUT`) |
| `CHAT_ACK` | delivery ACK (see above) | — |
| `READ_IN` | read receipt — advances your read watermark (`correlationId` = last-read server `messageId`) | no (updates the conversation receipt) |
| `SYSTEM_ACK` | confirm a must-arrive notice | retracts draft |

**You may NOT create system/technical/outbound types.** `CHAT_OUT`, `SIGNAL_OUT`, `TYPING_OUT`, `READ_OUT`,
`PRESENT_*`, `SERVICE_OUT`, and **system notices (`SYSTEM_OUT`)** are **server-originated**; sending any of
them is rejected (`unsupported inbound message type`). System notices are emitted only by the platform via
`POST /api/system/notify` (**ADMIN/SERVICE** only). Signaling has its full type set (`SIGNAL_IN/OUT/ACK`).

**Outbound — server → client** you should handle: `CHAT_OUT`, `CHAT_ACK` (your `SENT` receipt),
`SIGNAL_OUT`, `TYPING_OUT`, `READ_OUT`, `PRESENT_INIT/JOIN/LEAVE`, `SYSTEM_OUT`, `SERVICE_OUT` (transient
service notice, e.g. ingress overload).

## Timestamps & timezone

Every inbound message must carry a **sane `senderTimestamp` (epoch ms) and `senderTimezone` (IANA tz)**.
They are validated — a clock skew beyond the allowed window is rejected. Send real device time + zone.

## Metadata

- **Message `meta`** — an opaque string map for anything the app needs (e.g. `orderId`; for attachments
  the server fills `objectKey`/`fileName`/`contentType`/`sizeBytes`). Carried through delivery verbatim.
- **Session/frame fields** — `conversationId`, `correlationId`, `ackId`, `payload.kind`
  (`text`/`custom`/`event`/`attachment`), `payload.body`. The server adds `serverTimestamp`, the canonical
  `messageId`/`id`, and `sequenceNumber`.

## Presence & participant lists

- **The list of your chat partners / patterns comes from a separate platform site** (the directory /
  profile services + Kratos), **not** from the messenger. The messenger only knows the `(client, master)`
  pairs it has chats for.
- **The online/offline list arrives as presence events** over WS: `PRESENT_INIT` (snapshot on connect),
  `PRESENT_JOIN` (a peer came online), `PRESENT_LEAVE` (a peer went offline). Maintain your online set from
  these deltas; `GET /api/presence` is a snapshot fallback.

## Delete, freeze & retention

- **Delete is "delete for me"** — deleting a chat or a message hides it **for you only** (a per-side
  watermark); rows are never removed, the peer is unaffected, admins see everything, and new activity
  revives a deleted chat in your list. Read deleted history back with `?includeDeleted=true`. Delete does
  **not** stop messaging.
- **You can delete only your OWN message, and only while it is NOT frozen** (someone else's → `403`; frozen →
  `409`). **Address it by either id:** the server `messageId` — `DELETE /api/chats/{id}/messages/{serverMessageId}`
  — or, when you only hold your local id (e.g. before/without the `CHAT_ACK`), the client-original id:
  `DELETE /api/chats/{id}/messages?clientMessageId=<localId>`. Send **both** once you have them
  (`?backendId=<serverId>&clientMessageId=<localId>`) and the match is **strict on both** — the exact
  row, no cross-column collision; send **one** and it matches by that id. (The path form
  `/messages/{id}` takes a single id of either kind.)
- **Freeze makes the chat evidentiary.** When the parties reach a contract for an order, *that order's*
  messages are frozen (one-way, message-level, scoped by `orderId`) and become immutable proof for the
  negotiation. Sibling-order messages in the same chat stay deletable.
- **Retention** — normal history is kept for a TTL; **frozen** messages have their own, longer TTL. A
  delete is only "for me" **until the retention sweep** eventually purges the row per its class.
- **Block is the only messaging stop.** Either side can block the peer (mutual); while blocked, sending is
  rejected for both. Unblock resumes. (Delete does not stop messaging; block does.)

---

See also: [WebSocket & REST API reference](API.md) · [Architecture](ARCHITECTURE.md) ·
[Storage & guarantees](STORAGE.md) · [Configuration](CONFIGURATION.md).
