# Configuration & tuning

Every runtime knob is environment-overridable and **validated at startup**: a mis-set
value fails the boot with the full list of problems (`ConfigValidator`,
`@Observes StartupEvent`) — never a subtle under-load pathology.

> **The canonical, exhaustive reference is
> [`application.yaml`](../src/main/resources/application.yaml)** — every key carries
> an inline tuning hint (direction of effect + relevant bound). This page is the
> operator's guide; the yaml is the source of truth.

- [How overriding works](#how-overriding-works)
- [Environment variables](#environment-variables)
- [Key performance tunables](#key-performance-tunables)
- [Limits & flow control](#limits--flow-control)
- [Lifecycle & sweeps](#lifecycle--sweeps)
- [Integration & platform settings](#integration--platform-settings)
- [What the validator enforces](#what-the-validator-enforces)
- [Cross-cutting trade-offs](#cross-cutting-trade-offs)

---

## How overriding works

The service runs under the Quarkus **`prod`** profile; all keys below live in the
`%prod` section of `application.yaml`.

Two override styles are in play:

1. **Explicit placeholders** — hosts, credentials and pool sizes are declared as
   `${ENV_VAR:default}` and are listed in the
   [environment variables](#environment-variables) table.
2. **Everything else** — every other key (all the `processing.*` tunables) is
   overridable through the standard MicroProfile Config mapping: uppercase the key,
   replace `.` and `-` with `_`. For example:

   ```bash
   PROCESSING_MESSAGES_CREDITS_REFILL_RATE_PER_S=200
   PROCESSING_MESSAGES_OUTBOUND_BATCH_SIZE=3000
   PROCESSING_RATE_LIMIT_ENABLED=false
   ```

No key requires a rebuild; all take effect on restart.

> **Change the default credentials in production.** The `${…:default}` fallbacks
> (`ant`/`ant` for Postgres, `hormiga`/`hormiga123` for MinIO) exist so a dev
> environment boots with zero setup — they are not production values.

## Environment variables

| Env var | Default | Purpose |
|---|---|---|
| `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD` | `postgres`/`5432`/`hormigasdb`/`ant`/`ant` | PostgreSQL (history, outbox, conversation, attachment, dead_letter) |
| `DB_POOL_PERSIST` / `DB_POOL_POLLER` | `24` / `12` | Separate reactive pools: group-commit writes vs the outbox poller, so a persist burst can never starve live delivery. Total connections = the sum |
| `REDIS_HOST`/`REDIS_PORT` | `redis`/`6379` | Redis (presence, idempotency, watermarks, dead-letter confirmed-set) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Inbound `order.events` consumer. The default must be a **resolvable** host even without a broker — the consumer retries in the background and readiness is not gated on it |
| `MINIO_ENDPOINT` | `http://localhost:9000` | MinIO for attachments — **must be reachable by clients' browsers** (the host is baked into presigned URLs) |
| `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY`/`MINIO_BUCKET` | `hormiga`/`hormiga123`/`messenger-attachments` | MinIO credentials + bucket (created lazily) |
| `LOG_FILE` | `messenger.log` | Rotating file-log path (20 MB × 10 files, date-stamped, rotate-on-boot). Deployments set `/var/log/messenger/messenger.log` |

The JVM memory bounds (`-Xmx`, direct/metaspace/code-cache caps, `MALLOC_ARENA_MAX`)
are applied by the deployment, not the app — see [DEPLOYMENT](DEPLOYMENT.md).

## Key performance tunables

Under `processing.messages.*` unless noted. The single biggest throughput lever is
the **inbound persist group-commit**; the measured effect of every knob here
(scaling curve, latency, the group-commit story) is in [PERFORMANCE](PERFORMANCE.md).

| Param | Default | Effect |
|---|---|---|
| `inbound.persist-batch.max-size` | `64` | Messages coalesced into one `history+outbox` transaction. ↑ higher ceiling, fatter tx |
| `inbound.persist-batch.linger-ms` | `5` | Max wait to fill a partial batch — the only latency batching adds |
| `inbound.persist-batch.max-concurrent-batches` | `8` | Concurrent batch transactions. **Keep ≤ the persist DB pool** (validator-enforced) |
| `read-batch.{max-size,linger-ms,max-concurrent-batches}` | `64`/`10`/`4` | READ-receipt group-commit, mirroring persist. Shares the persist pool — mind the combined concurrency (see trade-offs) |
| `inbound.queue-size` | `3000` | Inbound admission gate; over it a send is shed with an `overloaded` notice |
| `outbound.batch-size` | `1500` | Rows claimed per outbox drain. Keep `< outbound.queue-size` |
| `outbound.queue-size` | `5000` | Outbound dispatch queue capacity; gates the poller |
| `outbound.polling-ms` | `1000ms` | Base poll cadence — the delivery-latency floor the regulator modulates upward |
| `feedback.{additional-ms,adjustment-factor,recovery-factor,max-ms}` | `1000`/`2.0`/`0.5`/`30000` | Adaptive poll-delay regulator: back off on drops, recover when stable. Factor bounds validator-enforced (`>1`, `<1`, `max ≥ base`) |
| `channel.{retry,min-backoff-ms,max-backoff-ms,max-retries}` | `true`/`300`/`1000`/`3` | Live WS-push retry with backoff |
| `stream-retry.{min,max}-backoff-ms` | `200`/`5000` | Re-subscribe backoff for the long-lived reactive streams — a transient fault never permanently kills a stream |
| `quarkus.redis.{max-pool-size,max-pool-waiting}` | `24`/`2048` | Sized for connection-churn storms: a mass disconnect fires presence + watermark + idempotency ops at Redis at once; Vert.x defaults (6/24) reject the burst |

## Limits & flow control

| Param | Default | Effect |
|---|---|---|
| `messages.credits.max-value` | `1000` | Per-connection burst allowance (WS flood guard) |
| `messages.credits.refill-rate-per-s` | `100` | Per-connection sustained rate. **Aggregate ceiling ≈ connections × this.** Raising it weakens the abuse guard |
| `quarkus.websockets-next.server.max-message-size` | `65536` | Hard cap on a single WS frame (64 KiB). A client must never build a larger frame — attachments go through presigned upload, not the socket |
| `rate-limit.enabled` | `true` | Kill switch for all REST rate limiting |
| `rate-limit.default-limit.{permits-per-second,burst}` | `20`/`40` | REST token bucket per caller × endpoint group (over → `429`) |
| `rate-limit.groups.attachments` | `1`/`5` | Strictest group — presigned-upload provisioning is expensive |
| `rate-limit.groups.admin` | `5`/`15` | Admin console |
| `rate-limit.{bucket-cache-max,bucket-idle-minutes}` | `200000`/`10` | Memory bound on distinct (group, caller) buckets and their idle eviction. Single-instance, in-memory |
| `attachments.max-size-bytes` | `26214400` | 25 MiB cap — enforced twice: declared size at request, actual object size at confirm |
| `attachments.allowed-content-types` | *(absent = all)* | Whitelist, exact or `type/*` wildcard; violations → `415`. Set one in production |
| `minio.{upload-ttl-seconds,download-ttl-seconds}` | `600`/`300` | Presigned URL lifetimes. `orphan-age-seconds` must exceed the upload TTL (validator-enforced) so the reaper never reclaims an upload whose PUT window is still open |
| `session.{idle-timeout-ms,max-pending,overload-kick-batch}` | `35000`/`1000`/`100` | Liveness + overload reapers: silent-connection cutoff, per-recipient un-ACKed backlog before force-disconnect, kick batch per sweep |
| `messages.idempotent.ttl-seconds` | `30` | Redis dedup window; keep above the retry/poll/feedback horizon |
| `messages.conversation-cache.{max-size,ttl-seconds}` | `100000`/`60` | In-process L1 for the hot membership/block lookup, write-through invalidation + TTL backstop. Single-instance; a Redis L2 is the documented multi-instance path |

## Lifecycle & sweeps

| Param | Default | Effect |
|---|---|---|
| `shutdown.drain-timeout-ms` | `5000` | Graceful-shutdown budget per batcher: flush in-flight commits before stop; anything un-committed is redelivered |
| `messages.collector.every-s` | `10s` | Watermark GC sweep of the outbox |
| `deadletter.{cleanup-batch,cleanup-every}` | `500`/`30s` | Dead-letter retract sweep |
| `retention.{history-days,frozen-days,every}` | `90`/`365`/`24h` | History retention; frozen (contract) messages keep the longer TTL — `frozen-days ≥ history-days` is validator-enforced |
| `attachments.{orphan-age-seconds,cleanup-batch,cleanup-every}` | `3600`/`200`/`15m` | Orphan-upload reclaim (PENDING uploads that never confirmed) |
| `session.{reaper-every,overload-reaper-every}` | `15s`/`20s` | Reaper cadences |

## Integration & platform settings

Settings an operator should know about even though they rarely change:

| Key | Value | Why it matters |
|---|---|---|
| `messages.storage.service` | `redis` | Backend selector: `redis` wires the Redis presence/idempotency/watermark beans; **any other value disables them**. Do not change casually |
| `mp.messaging.incoming.order-events-in` | topic `order.events`, group `hormiga-messenger`, `auto.offset.reset=earliest`, `failure-strategy=fail` | The Kafka consumer contract: at-least-once with idempotent handlers — a failed message nacks and is redelivered |
| `…order-events-in.health-readiness-enabled` | `false` | An absent broker does **not** flip readiness DOWN — the consumer retries in the background. Re-enable once the broker is provisioned in the environment |
| `messenger.order-events.type.{master-interested,contract-reached}` | `order.master.interested` / `order.contract.concluded` | The event-type strings are config, not code — reconciling the contract with the Order service is an env override |
| `quarkus.websockets-next.server.auto-ping-interval` | `10s` | Server-side ping cadence; pairs with `session.idle-timeout-ms` (a client silent past 35 s is reaped) |
| `quarkus.swagger-ui.always-include` | `true` | Swagger UI is served **in production** at `/q/swagger-ui` — acceptable only because the service lives behind the Ory edge; keep it unreachable from outside |
| `quarkus.flyway.migrate-at-start` | `true` | Schema is Flyway-managed (`db/migration`, V2–V10) |
| `quarkus.hibernate-orm.database.generation` | `validate` | Flyway owns the schema, so the ORM never patches it. There are no `@Entity` classes today (persistence is raw reactive SQL), so `validate` validates nothing — it just locks the intent for if entities are ever added |

## What the validator enforces

`ConfigValidator` runs on startup, accumulates **every** violation and refuses to
boot with the full list. Beyond positivity of all counts and durations, the semantic
invariants are:

- `persist-batch.max-concurrent-batches ≤` persist DB pool, and
  `read-batch.max-concurrent-batches ≤` the same pool
- `feedback.adjustment-factor > 1`, `feedback.recovery-factor < 1`,
  `feedback.max-ms ≥ additional-ms`
- `channel.max-backoff-ms ≥ min-backoff-ms`; same for `stream-retry`
- `retention.frozen-days ≥ history-days`
- `attachments.orphan-age-seconds ≥ minio.upload-ttl-seconds`
- every rate-limit group: `permits-per-second > 0`, `burst > 0`

## Cross-cutting trade-offs

- **Bigger queues + `batch-size`** → more RAM and higher latency under overload,
  fewer drops. Keep `batch-size < outbound.queue-size`.
- **Persist group-commit** (`persist-batch.*`) sets the inbound write ceiling; the
  only costs are `linger-ms` of latency and `max-size` rows at risk on a batch
  rollback (retried individually). The persist and read batchers **share one pool**:
  the validator checks each concurrency against the pool separately, so when raising
  both, keep the *sum* of `max-concurrent-batches` within `DB_POOL_PERSIST` yourself.
- **Faster polling / lower `additional-ms`** → lower delivery latency and faster
  drain, but more `SKIP LOCKED` claims and Redis round-trips even when idle.
- **Aggressive feedback** (`adjustment-factor` ↑, `recovery-factor` → 1) sheds load
  faster but drains slower with a higher tail after a burst; a very low
  `recovery-factor` recovers fast but can oscillate.
- **`idempotent.ttl-seconds` too low** → a redelivery can outlive the window and
  double-deliver; too high → more resident Redis keys.
- **Credits refill ↑** raises aggregate throughput but weakens the per-connection
  abuse guard — change deliberately.
