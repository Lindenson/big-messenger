# HormigaMessanger — Use-Case & Requirement Register

Authoritative index of the `UC-*` / `FR-*` codes the code, tests and docs reference. Until now these
codes lived **only inline** (Java comments, Karate scenario names, `@DisplayName`s) with no central
catalog, so descriptions drifted between sites and coverage was unknowable. This file is the register.

**Legend** — Test columns: **K** = Karate scenario (`e2etests/.../karate/**`), **WS** = JDK-WebSocket
JUnit suite (`e2etests/src/test/java/hormiga/ws/*WsTest`), **JU** = other JUnit unit/integration test,
**—** = none. Coverage as of 2026-07-20.

## Use cases

| Code | Description | K | WS | JU | Notes |
|---|---|:--:|:--:|:--:|---|
| UC-U01 | Create a chat via REST (idempotent on the pair) | ✅ | – | ✅ | also UC-H02 (same op, REST-driven) |
| UC-U03 | List a participant's chats **and** soft-delete ("delete for me", per-side watermark) | ✅ | – | ✅ | one code spans list + soft-delete; see Consistency |
| UC-U10 | Persistent message delivered to the online recipient | ✅ | ✅ | – | |
| UC-U11 | (bundled with U10 as `U10/U11`) | ✅ | ✅ | – | not a standalone code — sub-part of U10 |
| UC-U12 | Message to an OFFLINE recipient held (no loss) + recovered on reconnect | ✅ | ✅ | – | |
| UC-U13 | Delivery status SENT→DELIVERED on the recipient ACK | ✅ | ✅ | ✅ | |
| UC-U14 | Read receipts via the `peerLastReadId` watermark (✓✓) | ✅ | ✅ | ✅ | READ per-message status retired; watermark model |
| UC-U21 | A participant may hard-delete only their OWN message (by server or client-original id) | ✅ | – | ✅ | |
| UC-U22 | Freeze is message-level scoped by `orderId` (no chat-wide freeze); frozen ⇒ immutable | ✅ | – | ✅ | |
| UC-U23 | History retention: normal vs frozen classes, separate TTLs | – | – | ✅ | `HistoryRetentionSchedulerTest` (see FR-RET-04/05) |
| UC-U30 | WebRTC signaling relay, non-persistent (Strategy S) | ✅ | ✅ | – | paired with UC-H06 |
| UC-U40 | A connected client is tracked as present | ✅ | ✅ | – | |
| UC-U41 | Failed live delivery to an open session → mark recipient offline | – | – | ✅ | unit `DelivererTest`; e2e gap (invariant) |
| UC-U50 | Cursor-paginated conversation history (default=latest N, `before` scroll-up, `since` catch-up) | ✅ | – | ✅ | before-cursor added 2026-07-20 |
| UC-U60 | Identity/authorization: the Ory `X-User-*` header contract accepted/rejected | ✅ | – | ✅ | overlaps FR-SEC-01 |
| UC-U61 | A non-participant cannot read/delete (403) | ✅ | – | – | scenarios in chat-lifecycle / chat-moderation / signaling |
| UC-M10 | Sender receives a server ACK (SENT) with `serverMessageId` | ✅ | ✅ | – | ACK now echoes the server id (2026-07-20) |
| UC-H01 | Kafka `order.master.interested` → create the (client,master) chat | – | – | ✅ | `OrderEventConsumerTest`; no e2e (Kafka) |
| UC-H02 | Create chat via REST is idempotent | ✅ | – | ✅ | = UC-U01, REST twin of UC-H01 |
| UC-H03 | `orderId` is opaque order-metadata carried on messages | – | – | ✅ | metadata convention, NOT a behavior UC; exercised via UC-U22 + `MessageMapperTest` |
| UC-H04 | Kafka `order.contract.concluded` → freeze that order's messages | – | – | ✅ | `OrderEventConsumerTest`; no e2e (Kafka) |
| UC-H06 | WebRTC signaling (paired with UC-U30) | ✅ | ✅ | – | |
| UC-H07 | Block is the only messaging stop (block/unblock, per-side) | ✅ | ✅ | ✅ | best-covered |
| UC-H08 | Overload/ingress-reject notice to the sender (`SERVICE_OUT`, fire-and-forget) | – | – | ✅ | notice-shape unit-tested (`WebsocketServiceOverloadNoticeTest`); the overload TRIGGER itself is a degraded path, not e2e-driven |

## Functional requirements

| Code | Description | K | WS | JU | Notes |
|---|---|:--:|:--:|:--:|---|
| FR-CHAT-02 | Idempotent chat create | – | – | ✅ | overlaps UC-U01/H02 |
| FR-MSG-01 | Send-guard: sender is a member and not blocked | – | – | ✅ | overlaps UC-H07; `ConversationGuard`/`ConversationsTest` |
| FR-PRES-03 | Session-liveness reaper (idle + overloaded connections) | – | – | ✅ | `SessionLivenessSchedulerTest` |
| FR-RET-04/05 | History-retention sweep (normal + frozen classes) | – | – | ✅ | `HistoryRetentionSchedulerTest`; = UC-U23 |
| FR-SEC-01 | Ory proxy-header identity contract | – | – | ✅ | `IdentityHeadersTest`; overlaps UC-U60 |

## Consistency notes (resolved / accepted)

- **No external spec in-repo.** Numbering is non-contiguous (missing UC-U04–09, U15–20, U24–29, U31–39,
  U42–49, U51–59; UC-H05) — an authored specification is presumed to exist outside the repo. This register
  is reconstructed from inline references; treat descriptions here as authoritative going forward and keep
  code comments pointing at these codes.
- **UC-U02 retired.** It was a phantom (only in a feature title, no scenario/behavior). Removed from the
  `chat-lifecycle.feature` title (2026-07-20).
- **UC-U03 spans two behaviors** (list chats + soft-delete). Kept as one code but documented above; split
  into two codes only if the external spec distinguishes them.
- **UC-U11 / FR-RET-05** are sub-parts written only inside slashed pairs (`U10/U11`, `FR-RET-04/05`); not
  independent codes.
- **UC-U60 vs UC-U61 title fixed.** `auth.feature` now claims only UC-U60; UC-U61 (non-participant 403)
  is exercised in chat-lifecycle / chat-moderation / signaling (title comment added).
- **UC-H01/H04 (Kafka) have no e2e** — only `OrderEventConsumerTest` (unit). Accepted: the dual-driven
  REST twin (UC-H02) is Karate-covered; a Kafka e2e harness is out of scope for the current e2etests module.
- **UC-H08 now unit-covered** — the reject-notice construction was extracted (`overloadNotice`) and
  unit-tested for shape (`SERVICE_OUT`, correlationId = rejected id). Only the overload *trigger* (ingress
  queue full) is a degraded path left out of e2e — hard to saturate deterministically.
- **The 5 previously-"untested" codes are closed:** UC-H08 → unit test; FR-PRES-03 → labeled
  `SessionLivenessSchedulerTest`; FR-RET-04/05 → labeled `HistoryRetentionSchedulerTest`; UC-H03 → metadata
  convention (covered via UC-U22 + `MessageMapperTest`); UC-U02 → retired (phantom).
