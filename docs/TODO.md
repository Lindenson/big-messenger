# TODO / planned

Ideas captured but **not scheduled**. Fixing them here so they aren't lost; each needs a design
pass + ADR before implementation.

## Reliable broadcast to all ("announce to everyone")

**Gap.** There is no *guaranteed* fan-out-to-everyone. What exists today:

| | Guarantee | Reach |
|---|---|---|
| Chat / system notice | guaranteed (History / dead_letter) | unicast (per-recipient) |
| Presence (`PRESENT_INIT/JOIN/LEAVE`) | best-effort, ephemeral | broadcast, **online only** |
| reliable announce-to-all | — | **missing** |

Presence broadcasts best-effort via `Deliverer.sendBroadcast` (`recipientId="BROADCAST"` →
Vert.x `connection.broadcast()`), routed `INBOUND_DIRECT`; a miss self-heals through the
`PRESENT_INIT` snapshot on (re)connect.

**Why it's not just "reuse the guarantee path".** The guarantee machinery is inherently
per-recipient (one outbox row per recipient; one `dead_letter` DRAFT retracted by that recipient's
`SYSTEM_ACK`). A single broadcast has no "everyone-acked" semantics, and durable fan-out to all =
N unicasts (write amplification, doesn't scale). `broadcast()` also reaches only open sockets, not
offline members.

**Proposed approach (when needed) — generalize the presence pattern, do NOT use per-recipient dead_letter.**
Best-effort broadcast to online clients **+ snapshot-on-connect (pull)**: store the current
announcements server-side, broadcast to the online set, and have every client fetch the snapshot on
(re)connect. Converges to "eventually everyone sees it" without N durable rows. This is exactly the
shape `PRESENT_INIT` already uses — the building block (`broadcast + init-snapshot`) exists, applied
only to presence today.

**Status:** planned, unscheduled. Needs an ADR (broadcast strategy + retention/expiry of announcements
+ who may emit).

## PWA install + Web Push offline fallback (iOS-capable)

**Goal.** When a client is OFFLINE and a message arrives, proactively nudge them via **Web Push**
("New message from X") so they open the app. The message *content* still flows over the existing
secure path (WS + history-sync on reconnect) — the push is only a wake-up. Ship small-messanger as an
installable **PWA** (this is also the precondition for Web Push on iOS).

**Why it fits our architecture.** The server already detects an offline recipient on the delivery
path (`Deliverer.deliver` finds no live session → `coordinator.passive`; the message is held in the
`outbox`). That is exactly the trigger point — the push is the offline-fallback channel complementing
the outbox (outbox delivers the content on reconnect; push tells the user to reconnect).

**iOS constraint (decisive).** Safari Web Push works **only for a PWA added to the Home Screen**
(iOS 16.4+, standalone mode) — NOT in a regular Safari tab. It uses the standard **Web Push API +
Service Worker + VAPID**; no APNs certs / Apple Developer account are needed for the *web* route
(only a native Capacitor wrapper would use APNs). Requires the user to install the PWA and grant
notification permission (prompt only on a user gesture). Android/desktop have no such install
requirement.

**Pieces.**
- *Frontend (small-messanger PWA — its repo):* `manifest.json`, a service worker (`push` +
  `notificationclick` handlers), subscribe via VAPID public key → POST the `PushSubscription`
  (endpoint + keys) to the backend.
- *Subscription store:* per-user push subscriptions (a new table).
- *Server sender:* a web-push library sending encrypted VAPID pushes to subscription endpoints.
- *Trigger + port:* a `PushNotifier` port + web-push adapter (hexagonal), fired on the offline-delivery
  path (`Deliverer` empty-sessions / `passive`) or by a reader over undelivered outbox rows for offline
  recipients.
- *VAPID keypair* (secret → env, never committed).

**Design.** The push carries a **lightweight nudge only** ("New message"), NOT content — payload
limits + privacy, and the content already has a secure path. Conceptually the same advisory-nudge
shape as `SystemNotifier`. Coalesce/rate-limit pushes so an offline burst yields one nudge, not N.

**Caveats.** iOS needs PWA install + permission; delivery is best-effort; service worker is
same-origin; dedupe pushes per (recipient, window).

**Status.** Planned — **plan drafted as ADR-021** (governance repo:
`architecture/adr/drafts/ADR-021-messenger-web-push-offline.md`): `PushNotifier` port + `WebPushAdapter`
(VAPID), `push_subscription` store, **coalesced offline-nudge trigger** off the `Deliverer` offline branch
(`Deliverer.java:59-63` → `coordinator.passive`) so poller retries don't spam, nudge-only payload, and a
5-phase plan (P0 subscription/VAPID → P1 notifier+trigger → P2 frontend PWA → P3 lifecycle → P4 iOS UX).
Frontend part lives in the small-messanger repo; server `PushNotifier` port + adapter in the messenger.
