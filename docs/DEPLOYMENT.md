# Deployment

- [Dependencies](#dependencies)
- [Recommended bring-up order](#recommended-bring-up-order)
- [Running the service](#running-the-service)
- [Two non-negotiables](#two-non-negotiables)
- [JVM memory bounds](#jvm-memory-bounds)
- [Future scaling](#future-scaling)

---

## Dependencies

| Service | Role | Required? |
|---|---|---|
| **PostgreSQL** | durable truth — History, Outbox, conversation, attachment, dead_letter | **yes** |
| **Redis** | rebuildable cache — presence, idempotency, Tetris watermark, dead-letter confirmed-set | **yes** (degrades safely if down — see [STORAGE](STORAGE.md#redis-is-a-rebuildable-cache)) |
| **MinIO** (or S3-compatible) | attachment object store (presigned upload/download) | for attachments |
| **Kafka** | inbound `order.events` (create-chat / freeze) | optional — absent broker just retries in the background; readiness is not gated on it |
| **Ory (Kratos + Oathkeeper)** | identity: session auth + the trusted `X-User-*` header injection; also the source of the **participant/directory lists** the client uses | **yes, in production** |

## Recommended bring-up order

Bring the stateful backends up first, then the edge, then the app:

1. **PostgreSQL** — Flyway migrates the schema at startup (`migrate-at-start`).
2. **Redis** — presence/idempotency/Tetris; the app cold-start-cleans it non-blockingly.
3. **MinIO** — create nothing by hand; the bucket is created lazily. Set `MINIO_ENDPOINT` to a
   **browser-reachable** host (it is baked into presigned URLs).
4. **Kafka** *(optional)* — provision `order.events` on the Order side; the consumer is idempotent.
5. **Ory (Kratos + Oathkeeper)** — the edge authenticates sessions and injects `X-User-*`. The client
   directory / online-eligible participant lists come from the platform (Kratos + profile services), not
   the messenger.
6. **The messenger app** — bound to the above by hostname via env vars ([CONFIGURATION](CONFIGURATION.md)).

## Running the service

Same model as the other Hormigas services: run the pre-built `quarkus-app` jar, bind to the shared
platform services by hostname, join the shared network.

```bash
JAVA_HOME=/path/to/jdk-25 ./mvnw -DskipTests package
DB_HOST=… REDIS_HOST=… MINIO_ENDPOINT=http://<reachable>:9000 \
  java -jar target/quarkus-app/quarkus-run.jar        # → :8080 (behind the Ory edge)
```

## Two non-negotiables

- 🔐 **Run only behind the Ory Oathkeeper edge.** The service trusts `X-User-*` and does **not** validate
  JWTs, so a directly-exposed `:8080` lets anyone forge any identity (incl. `X-Role: ADMIN` against
  `/api/system/notify`). Publish exclusively via the edge; never expose the port to the internet.
- 🪣 **`MINIO_ENDPOINT` must be browser-reachable.** It is used for the app's own calls **and** baked into
  presigned upload/download URLs — set it to the host the frontend reaches. `localhost:9000` is dev-only.

## JVM memory bounds

Run the JVM bounded, or virtual-address reservation balloons to tens of GB. The deployment uses:

```
MALLOC_ARENA_MAX=2
-Xmx2g -XX:MaxDirectMemorySize=1g -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=256m
```

With these: VIRT ~3.3 GB, RSS ~0.7–1 GB under load, no full GC — see [PERFORMANCE](PERFORMANCE.md#memory).

## Future scaling

The design is sharding-ready; the staged path (see [STORAGE](STORAGE.md#why-it-shards-without-a-rewrite)):

- **Horizontal app scaling** — `CRC32(clientId) mod N` shard key at the gateway + per-instance outbox
  filtering. Shared state is already centralized in Redis; outbox claiming is already `SKIP LOCKED` + lease,
  so instances co-drain safely today.
- **Redis → cluster (master/replica + Sentinel)** — drop-in; state is rebuildable and Tetris keys are
  per-recipient (hash-slot cleanly). A clustered/HA Redis only shrinks the already-safe loss window.
- **PostgreSQL sharding** — by `conversationId`/`clientId`; a conversation is a self-contained pair.
- **MinIO → sharded buckets or S3** — a config change; the service stores only `objectKey` and mints URLs
  on demand. (Split-horizon public-vs-internal MinIO URLs is a tracked follow-up.)
