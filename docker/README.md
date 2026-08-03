# Running HormigaMessanger with Docker

Everything needed to build and run the messenger as a container — **no Kubernetes required**.
This folder is the deployment unit: a `Dockerfile`, a full `docker-compose.yml` stack, the
environment template, and the Prometheus/Grafana wiring.

> **There is no Kubernetes yet.** When it arrives, the image built here is what you deploy; the
> `.env` keys below become a ConfigMap/Secret and `prometheus.yml` becomes a ServiceMonitor.

---

## TL;DR

```bash
cp docker/.env.example docker/.env          # then edit the secrets (DB_PASSWORD, MINIO_SECRET_KEY)
docker compose -f docker/docker-compose.yml up -d --build
```

That builds the image from source and starts: **messenger · postgres · redis · minio (+bucket) ·
prometheus · grafana**. Then:

| What | URL |
|---|---|
| Health (readiness) | http://localhost:8080/q/health/ready |
| Metrics (Prometheus scrape) | http://localhost:8080/q/metrics |
| API docs (Swagger UI) | http://localhost:8080/q/swagger-ui |
| Prometheus | http://localhost:9090 |
| Grafana (dashboard auto-provisioned) | http://localhost:3000 |
| MinIO console | http://localhost:9001 |

```bash
docker compose -f docker/docker-compose.yml logs -f messenger   # tail
docker compose -f docker/docker-compose.yml down                # stop (keep data)
docker compose -f docker/docker-compose.yml down -v             # stop + wipe volumes
```

---

## ⚠️ Two things that will bite you

**1. Memory — the JVM's virtual memory balloons to ~24 GB unless bounded.** The image already bakes
the fix (`MALLOC_ARENA_MAX=2` + `-Xmx2g -XX:MaxDirectMemorySize=1g -XX:MaxMetaspaceSize=256m
-XX:ReservedCodeCacheSize=256m`), so a container settles at **VIRT ~3.2 GB / RSS ~0.8 GB**. If you
override `JAVA_OPTS`, keep those bounds and keep `MALLOC_ARENA_MAX=2`, or set a container `mem_limit`
(compose sets `3g`). This is not optional — it's the single most important operational fact.

**2. Security — never expose `:8080` publicly.** The service trusts edge-injected identity headers
(`X-User-Id` / `X-User` / `X-Role` / `X-User-Email`) and does **no** in-app token validation. It MUST
sit behind the **Ory edge (Oathkeeper)**, which authenticates the session and injects those headers.
A directly-reachable `:8080` lets anyone send `X-Role: ADMIN` and own the admin API. The compose
publishes `8080` for local convenience only — remove that port mapping (or firewall it) in any shared
environment and route through Oathkeeper.

---

## Build the image on its own

```bash
# From the repo ROOT (build context must include src/ + pom.xml):
docker build -f docker/Dockerfile -t hormiga-messenger:local .
```

Two-stage: JDK 25 builds the Quarkus fast-jar (tests skipped — CI runs them), JRE 25 runs it as a
non-root user with a container `HEALTHCHECK`.

---

## Running against EXTERNAL dependencies

The compose bundles Postgres/Redis/MinIO for convenience. To use your own instead: delete those
services from `docker-compose.yml` (and `minio-init`), then point the app at your hosts in `.env`:

```dotenv
DB_HOST=pg.internal      DB_PORT=5432   DB_NAME=hormigasdb   DB_USER=…   DB_PASSWORD=…
REDIS_HOST=redis.internal
MINIO_ENDPOINT=https://s3.example.com          # must be reachable BY CLIENTS (presigned URLs)
KAFKA_BOOTSTRAP_SERVERS=broker.internal:9092   # optional; absent broker still boots
```

To run only the app + observability against external deps:
`docker compose -f docker/docker-compose.yml up -d messenger prometheus grafana`.

---

## Environment variables

Full template with comments in [`.env.example`](.env.example). Summary:

| Var | Default | Purpose |
|---|---|---|
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | postgres 5432 hormigasdb ant — | Postgres (durable store) |
| `DB_POOL_PERSIST` `DB_POOL_POLLER` | 24 / 12 | isolated reactive pools (writes vs outbox poller) |
| `REDIS_HOST` `REDIS_PORT` | redis 6379 | presence · watermark GC · idempotency |
| `MINIO_ENDPOINT` `MINIO_ACCESS_KEY` `MINIO_SECRET_KEY` `MINIO_BUCKET` | http://minio:9000 … | attachment object store (endpoint must be client-reachable) |
| `KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | inbound `order.events` (optional) |
| `LOG_FILE` | /var/log/messenger/messenger.log | rotated 20 MB × 10 |
| `MALLOC_ARENA_MAX`, `JAVA_OPTS` | baked | memory guards (see above) |

Secrets (`DB_PASSWORD`, `MINIO_SECRET_KEY`) come from env / your secret store — never hardcoded, and
`.env` is git-ignored.

---

## Observability

- **Prometheus** scrapes `messenger:8080/q/metrics` (config in [`prometheus.yml`](prometheus.yml)); all
  meters are under the `messenger_*` namespace — catalogue in [`../docs/OBSERVABILITY.md`](../docs/OBSERVABILITY.md).
- **Grafana** auto-provisions the Prometheus datasource and loads
  [`../docs/ops/grafana-messenger-dashboard.json`](../docs/ops/grafana-messenger-dashboard.json)
  (mounted read-only). Default login `admin`/`admin` (anonymous view enabled for dev — turn off in prod).

---

## Kafka (optional)

`order.events` isn't provisioned yet, and the app boots fine without a broker (readiness for it is
disabled; the consumer retries in the background). To run a local Redpanda broker:

```bash
docker compose -f docker/docker-compose.yml --profile kafka up -d
# then set KAFKA_BOOTSTRAP_SERVERS=broker:29092 in .env and recreate the app
```

---

## CI

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs on push/PR to `main`: build + unit tests
(JDK 25) then a container-image build. E2E (Karate + WebSocket, JDK 21 against this stack) is documented
there as the next job to enable.
