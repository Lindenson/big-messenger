# Stage spec — real Ory Kratos identity integration

**Status:** DEFERRED — identity will be a **separate Go service** owned by the architect; its **API contract
will be provided later**. When it arrives, the messenger's `KratosClientDirectory` becomes a thin
`<GoService>ClientDirectory` adapter targeting **that service's API** (not Kratos directly). The
messenger keeps `StubClientDirectory` until then. Everything below stays valid as the *requirement* the Go
service must satisfy; **Appendix A (the shared identity schema) is the recommendation for that Go service.**
**Goal:** replace the stub `ClientDirectory` with a real identity source, so the service can list clients and
resolve `id → name + email` for identities that are *not* the current caller and *not* currently connected.
This is the **last functional gap before prod** — after it, only CI/CD remains.

---

## 1. Where we are today

| Piece | State |
|---|---|
| Per-request identity | **Real, done.** Edge (Ory) injects `X-User-Id` / `X-User` / `X-Role` / `X-User-Email`; the app trusts them (no in-app token validation) — `IdentityHeaders`, `WebSocketUtils`, `TokenVerifier`. |
| `ClientDirectory` port (`ports/identity/`) | Defined: `listClients()` + `findById(id)` → `ClientData`. |
| `StubClientDirectory` | Returns **empty** (`List.of()` / `null`). **Zero call sites** — a wired-but-unused seam. |
| `Conversation` domain | Stores **only** `clientId` + `masterId` (bare ids). No participant name/email anywhere. |

So today every shipping feature runs off the caller's own edge headers + the conversation/presence stores.
The directory is only needed the moment we must **show or pick an identity that isn't the current caller**.

---

## 2. The modern Ory stack (answering "какой сейчас Kratos + authproxy")

**Ory Kratos** — current line **v26.2.x** (v26.2.22, June 2026). Headless identity management.
- **Admin API** (default port **4434**, must be network-isolated):
  - `GET /admin/identities?page_size=250&page_token=<tok>` — paginated; next page token comes back in the
    `Link` header (`rel="next"`). Returns an array of Identity.
  - `GET /admin/identities/{id}` — one identity.
- **Identity** JSON shape (fields we care about):
  ```json
  {
    "id": "b1f...uuid",
    "schema_id": "default",
    "state": "active",
    "traits": { "email": "a@x.com", "name": { "first": "Ada", "last": "Lovelace" } },
    "verifiable_addresses": [{ "value": "a@x.com", "verified": true, "via": "email" }],
    "metadata_public": { "role": "CLIENT" }
  }
  ```
- **Admin API auth:** unauthenticated by default → **network-isolate** it (reachable only from the messenger).
  On **Ory Network** (managed) the same API is public and requires `Authorization: Bearer ory_pat_…`.

**The proxy — Ory Oathkeeper** (Identity & Access Proxy). Still the self-hosted choice and it already matches
our header contract:
- `authenticators.cookie_session` (or `bearer_token`) → calls Kratos `GET /sessions/whoami`.
- `mutators.header` → templates our four headers from the authenticated session, e.g.
  ```yaml
  mutators:
    header:
      enabled: true
      config:
        headers:
          X-User-Id:    "{{ print .Subject }}"
          X-User:       "{{ print .Extra.identity.traits.name.first }} {{ print .Extra.identity.traits.name.last }}"
          X-User-Email: "{{ print .Extra.identity.traits.email }}"
          X-Role:       "{{ print .Extra.identity.metadata_public.role }}"
  ```
- Oathkeeper also terminates access rules (which routes are public / role-gated) — it is the box that makes
  "never expose :8080 directly" true.

> **Kratos has no native RBAC.** `X-Role` must come from somewhere in the identity — recommended:
> `metadata_public.role` (admin-settable, not user-editable). This must be agreed with whoever owns the
> Kratos identity schema (see D3).

---

## 3. Design decisions (need sign-off)

| # | Decision | Options | Recommendation |
|---|---|---|---|
| **D1** | Kratos client tech | (a) Quarkus **REST Client Reactive** against the 2 admin endpoints (Mutiny-native, tiny). (b) Official `kratos-client-java` SDK (blocking OkHttp, heavy, needs worker-thread offload). | **(a)** — we need 2 endpoints; stay reactive, no blocking bridge, no fat dep. |
| **D2** | Caching | Directory data changes rarely; `findById` would fire on every chat-list render. (a) In-process **Caffeine**, TTL ~60 s + short negative cache. (b) Shared **Redis** cache. (c) None. | **(a)** — per-instance Caffeine, TTL 60 s, negative-cache 10 s. Simple, no cross-instance coupling; Kratos stays the source of truth. |
| **D3** | Trait → `ClientData` mapping | Depends on the Kratos identity schema. | **Resolved (proposed):** shared Hormiga identity schema in **Appendix A**; `name = {first,last}`, `email` trait, `role` in `metadata_public.role`. Mapping is **config-driven** (JSON-pointer keys) so schema tweaks don't need a rebuild. Awaiting approval of Appendix A. |
| **D4** | Admin API reachability & auth | (a) Self-host: network-isolate 4434, no creds. (b) Ory Network: `Bearer` PAT. | Support **both** via config: `KRATOS_ADMIN_URL` (required) + `KRATOS_ADMIN_TOKEN` (optional). Secret via env / `config/*.config.yml`, never hardcoded. |
| **D5** | Scope of consumers | The port has **no callers** today. (a) **Only swap** stub→Kratos (seam becomes real, nothing visibly changes). (b) Also wire the **first consumer(s)**: an admin "list clients" endpoint and/or counterparty name+email enrichment on chat create/list. | **Decide together.** My lean: (b-minimal) — one admin `GET /api/admin/clients` (list) + `findById` enrichment when creating a chat, so the feature is actually exercised and e2e-provable. Anything more is roadmap. |
| **D6** | Failure mode when Kratos is down | (a) Degrade: `findById` → null, messaging keeps working id-only (as today), log + metric. (b) Fail the request. | **(a)** — the directory is a **non-critical enrichment path**; it must **never block messaging**. Emit `messenger.directory.error` + fall back to id-only. |
| **D7** | Keep Oathkeeper vs move to Ory Network gateway | Self-hosted Oathkeeper (matches current header contract, full control) vs Ory Network managed. | **Keep Oathkeeper self-host** for now — zero change to the app's header contract; revisit at scale. |

---

## 4. Deliverables (once decisions are locked)

1. **`KratosClientDirectory`** adapter in `infrastructure/identity/` implementing `ClientDirectory` via the
   REST client; paginate `listClients` (follow `Link: next`), map traits→`ClientData` per D3.
2. **Config** (`@ConfigMapping`): `KRATOS_ADMIN_URL`, `KRATOS_ADMIN_TOKEN?`, cache TTLs, trait-mapping keys.
   Add cross-field validation to `ConfigValidator` (URL present in `%prod`).
3. **Cache** (D2) + **graceful degradation** (D6).
4. **Wire the stub out**: make `KratosClientDirectory` the `%prod` bean; keep `StubClientDirectory` for
   `%dev`/`%test` (or a Kratos testcontainer — see below). Guard with `@IfBuildProfile` / `@LookupIfProperty`.
5. **Consumers per D5** (if b): admin list endpoint + chat-create enrichment.
6. **Observability**: `messenger.directory.lookup|hit|miss|error` counters + latency timer; add to Grafana
   dashboard + OBSERVABILITY.md.
7. **ArchUnit**: adapter stays in `infrastructure`, core depends only on the port (already enforced — add a
   rule that `ports.identity` has no infra imports if not covered).
8. **Docs**: update DEPLOYMENT.md (Kratos + Oathkeeper compose), CONFIGURATION.md (new keys), ARCHITECTURE.md
   (identity source), CLIENT-CONTRACT.md (how `X-Role` is derived).

---

## 5. Testing — and a real upgrade to e2e fidelity

- **Unit**: trait→`ClientData` mapping (incl. missing/partial traits), pagination, negative cache.
- **Integration**: adapter against **WireMock** or a **Kratos testcontainer** seeded with identities.
- **e2e (big win)**: today's e2e **fakes the edge** by setting `X-User-*` headers directly. This stage lets us
  stand up **Oathkeeper + Kratos** in `e2etests/docker-compose.yml` and drive requests **through the proxy**
  with a real Kratos session cookie — proving the *entire* edge contract end-to-end (session → whoami →
  header mutation → app), which we cannot prove today. Recommended as part of this stage.

---

## 6. After this stage → prod

Only **CI/CD** remains: build the memory-bounded image, compose/K8s manifests (app + Postgres + Redis +
MinIO + Kafka + Kratos + Oathkeeper), and the pipeline (build → test → e2e → image → deploy). Tracked
separately.

---

## 7. Open questions for the architect

1. **Appendix A** — approve the proposed **shared Hormiga identity schema** (or amend traits/roles).
2. **D5** — swap-only, or swap + first consumer (admin list + chat enrichment)?
3. **D7** — confirm self-hosted Oathkeeper (not Ory Network) for prod.
4. Is there **already a Kratos instance** (dev/preprod) we should point at, or do we stand one up in compose?

---

## Appendix A — Proposed shared Hormiga identity schema (Messenger · Master · Client · TaskManager)

**Boundary principle.** Kratos owns **identity + credentials + authorization role + a minimal shared
profile** (name, email, phone, locale, avatar, status). **Everything project-specific stays out of Kratos** —
each service keeps its business data (master ratings/services, client orders, tasks…) in its own store, keyed
by the Kratos identity **`id`** (a stable UUID). One person = one Kratos identity, usable by all four apps.

### A.1 Identity schema (JSON Schema — validated by Kratos)

`traits` are the schema-validated identity fields. Ory extensions (`ory.sh/kratos`) declare which trait is a
login identifier and which drives verification/recovery.

```json
{
  "$id": "https://schemas.hormiga.io/identity.v1.json",
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "Hormiga Identity",
  "type": "object",
  "properties": {
    "traits": {
      "type": "object",
      "properties": {
        "email": {
          "type": "string",
          "format": "email",
          "title": "E-Mail",
          "minLength": 3,
          "maxLength": 320,
          "ory.sh/kratos": {
            "credentials": {
              "password":  { "identifier": true },
              "webauthn":  { "identifier": true },
              "passkey":   { "display_name": true },
              "code":      { "identifier": true, "via": "email" }
            },
            "verification": { "via": "email" },
            "recovery":     { "via": "email" }
          }
        },
        "name": {
          "type": "object",
          "title": "Full name",
          "properties": {
            "first": { "type": "string", "title": "First name", "maxLength": 128 },
            "last":  { "type": "string", "title": "Last name",  "maxLength": 128 }
          }
        },
        "phone": {
          "type": "string",
          "title": "Phone (E.164)",
          "pattern": "^\\+[1-9]\\d{6,14}$",
          "ory.sh/kratos": {
            "verification": { "via": "sms" }
          }
        },
        "locale": {
          "type": "string",
          "title": "Locale",
          "enum": ["es", "en", "uk", "ru"],
          "default": "es"
        },
        "avatar_url": {
          "type": "string",
          "format": "uri",
          "title": "Avatar URL",
          "maxLength": 2048
        }
      },
      "required": ["email"],
      "additionalProperties": false
    }
  }
}
```

Notes:
- **email** is the primary login identifier (password / passkey / email-code) and the verify+recovery channel.
- **phone** is an optional *verifiable contact* (SMS). Not a login identifier yet — promote later by adding
  `credentials.code.via: sms` when SMS infra is in place, no schema break.
- **name** is structured `{first,last}`; apps compose the display string. `avatar_url` is optional (nice for
  Messenger chat rows / TaskManager assignee chips).
- `additionalProperties: false` keeps the shared schema clean — project-specific fields do **not** go here.

### A.2 Authorization & status — `metadata_public` (admin-set, schemaless in Kratos)

Kratos has no RBAC and does not schema-validate metadata, so this is a **convention** set via the admin API.
`metadata_public` **is** returned by `/sessions/whoami`, so Oathkeeper can template it into headers;
`metadata_admin` is admin-API-only (never in whoami).

```jsonc
// identity.metadata_public  (visible to apps + Oathkeeper)
{
  "role":   "CLIENT",          // CLIENT | MASTER | ADMIN | SERVICE  → drives X-Role
  "status": "ACTIVE"           // ACTIVE | PENDING | SUSPENDED       → account gate
}

// identity.metadata_admin  (internal only, never exposed to apps)
{
  "kyc_level": 0,
  "notes": "…"
}
```

- **role** is the single primary authorization role (marketplace user is CLIENT *or* MASTER; ADMIN/SERVICE
  are pure authz). Set only by admins/provisioning — never user-editable. If a user ever needs multiple
  capabilities, add a `roles: [...]` array later; the single `role` stays as the primary for `X-Role`.
- **status** lets any Hormiga app refuse a suspended/pending account uniformly.

### A.3 Oathkeeper header mutator (maps this schema → our edge contract)

```yaml
mutators:
  header:
    enabled: true
    config:
      headers:
        X-User-Id:    "{{ print .Subject }}"
        X-User:       "{{ trimSpace (printf \"%s %s\" (print .Extra.identity.traits.name.first) (print .Extra.identity.traits.name.last)) }}"
        X-User-Email: "{{ print .Extra.identity.traits.email }}"
        X-Role:       "{{ print .Extra.identity.metadata_public.role }}"
```

### A.4 Messenger mapping (resolves D3)

| `ClientData` | Source | Fallback (keeps current `IdentityHeaders` behaviour) |
|---|---|---|
| `id` | `identity.id` | — (required) |
| `name` | `traits.name.first + " " + traits.name.last` (trimmed) | → `traits.email` → `id` |
| `email` | `traits.email` | null |
| `role` | `metadata_public.role` | null |

The `KratosClientDirectory` adapter reads these via config-driven JSON-pointer keys, so re-pointing a field
(e.g. a flat `traits.name` string) is a config change, not a rebuild.

### A.5 Per-project fit

| Project | Uses from Kratos | Keeps in its own store (keyed by `id`) |
|---|---|---|
| **Messenger** | id, name, email, role | conversations, messages, attachments, presence |
| **Master** | id, name, email, role=MASTER, avatar | master profile, services, ratings, availability |
| **Client** | id, name, email, role=CLIENT, avatar | orders, addresses, payment refs |
| **TaskManager** | id, name, email, role | tasks, assignments, boards, comments |
