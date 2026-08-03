# HormigaMessanger — E2E acceptance suite

Two complementary suites in this module, run together by one `mvn test`:

| Suite | Drives | Where |
|---|---|---|
| **Karate** (OSS 1.4.1) | REST use-cases (`UC-U##`/`UC-H##`) | `src/test/resources/karate/**.feature`, run by `MessengerRunner` |
| **JDK-WebSocket** (JUnit 5 + `java.net.http.WebSocket`) | all live-WS scenarios | `src/test/java/hormiga/ws/*WsTest.java` + `RateLimitRestTest` |

> WebSocket lives in a JUnit suite, not Karate: Karate 2.x paywalls WebSocket and 1.4.1's GraalVM JS
> handler can't build on this JDK. See ADR-015.

## Prerequisites

```bash
# 1. Infra (Postgres + Redis + MinIO)
docker compose -f e2etests/docker-compose.yml up -d

# 2. App — prod profile, pointed at local infra, JVM MEMORY-BOUNDED (or VIRT balloons to ~24 GB).
JAVA_HOME=/opt/jdk-25.0.2 ./mvnw -q clean package -DskipTests
MALLOC_ARENA_MAX=2 DB_HOST=localhost REDIS_HOST=localhost MINIO_ENDPOINT=http://localhost:9000 \
  java -Xmx2g -XX:MaxDirectMemorySize=1g -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=256m \
       -jar target/quarkus-app/quarkus-run.jar &

# ⚠ Before a run — clear any load-test leftovers, or a stale outbox backlog buries fresh WS deliveries:
docker exec e2etests-messenger-postgres-1 psql -U ant -d hormigasdb \
  -c "TRUNCATE outbox, dead_letter, message_history RESTART IDENTITY;"
```

Tests run on **JDK 21** (Karate); the app is built/run on **JDK 25**.

## Run

```bash
cd e2etests

# Full suite against local dev (default)
JAVA_HOME=/opt/jdk-21.0.2 mvn -q test

# Against preprod (one switch → both suites point at the preprod host preprod.example.com)
JAVA_HOME=/opt/jdk-21.0.2 mvn -q test -Ppreprod
```

## Groups (run a subset)

Each suite has its own standard filter (they compose in one run):

| Want | Command |
|---|---|
| **Smoke** — WS core (chat delivery, presence, security) | `mvn test -De2e.groups=smoke` |
| **Smoke** — Karate core (messaging) | `mvn test -Dkarate.tags=@smoke` |
| Karate by domain | `mvn test -Dkarate.tags=@chat` (or `@auth` / `@messaging` / `@signaling`) |
| The pending spec (red) | `mvn test -Dkarate.options="--tags @wip"` |

- **WS suite** (JUnit 5): filter with `-De2e.groups=<tag>` (`@Tag("smoke")` on the core WS tests).
- **Karate**: filter with `-Dkarate.tags=@<tag>`; `@wip` (spec-only) and `@ws` (retired in-feature WS)
  are always excluded from the default run.

**`PresenceRefreshWsTest`** (tag `presence`) proves the Redis presence self-heal: it `docker exec`s
`FLUSHALL` on Redis and waits for the refresh sweep to rebuild `/api/presence`. It needs Docker access to
the Redis container (`-De2e.redisContainer=…`, default `e2etests-messenger-redis-1`) and, to run fast, the
app started with `-Dprocessing.session.presence-refresh-every=3s` (else it waits for the 30 s default).

## Environments

| Env | baseUrl / wsUrl | How |
|---|---|---|
| `dev` (default) | `localhost:8080` | — |
| `preprod` | `preprod.example.com:8080` | `-Ppreprod` (sets `karate.env` + `e2e.baseUrl`/`e2e.wsUrl` together) |

Both suites read the target host the same way per environment: Karate from `karate.env` (`karate-config.js`),
the WS suite from `-De2e.baseUrl`/`-De2e.wsUrl` (`Rest.BASE`/`Rest.WS_URL`). The `preprod` Maven profile
sets all three so you never have to align them by hand.

## Layout
- `karate/auth/` — identity & authz (`@auth`)
- `karate/chat/` — lifecycle / admin / moderation (`@chat`)
- `karate/messaging/` — send/deliver/ack (`@messaging @smoke`)
- `karate/signaling/` — WebRTC signaling relay (`@signaling`)
- `hormiga/ws/*WsTest` — live WebSocket e2e (delivery, status, offline redelivery, presence, security,
  signaling, system-notice, typing); `RateLimitRestTest` — REST rate-limit 429.
