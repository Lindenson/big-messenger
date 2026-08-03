# Testing

Three layers: fast **unit/integration** (JDK 25), **e2e acceptance** (Karate REST + a JDK-WebSocket JUnit
suite, JDK 21), and **load** (Gatling). Plus **ArchUnit** rules that make the architecture style itself a
build-time test.

- [Unit & integration](#unit--integration)
- [Architecture rules (ArchUnit)](#architecture-rules-archunit)
- [End-to-end acceptance](#end-to-end-acceptance)
- [Load testing](#load-testing)
- [Coverage note](#coverage-note)

---

## Unit & integration

```bash
JAVA_HOME=/path/to/jdk-25 ./mvnw test          # Dev Services spin up Postgres + Redis in Docker
```

**417 unit/integration tests**, all green. Covers the reactive engine (backpressure, group-commit
batchers, router/pipeline/stages, poller, watermark/Tetris), domain invariants + validators, the Postgres
adapters/mappers, Redis presence/idempotency/Tetris, REST resources + rate-limit filter, and the
security-header identity contract.

## Architecture rules (ArchUnit)

`HexagonalArchitectureTest` — **22 rules, all green** — enforces the style at build time so it can't drift:

- **Layering** — domain is framework-free; core depends only on ports; ports never reach into core/infra;
  transport tech (Vert.x, JAX-RS, Kafka, MinIO) stays out of core.
- **Naming = the design, fixed in code** — no `*Service` in core (role-noun use-cases instead);
  `*Repository` is infrastructure-internal (never in core/ports, and no `repository` *package* in
  core/ports/domain); `*Adapter`/`*Mapper` only in infrastructure; a `*PostgresAdapter` lives in its
  aggregate subpackage (no catch-all `adapters/` folder).
- **Slices** — `@Path`→rest, `@WebSocket`→websocket, `@Incoming`→messaging, `@Scheduled`→scheduler; the WS
  ingress is pure transport; the router is the single crossroads for message delivery.

The full rationale and the naming/composition conventions are in [ARCHITECTURE](ARCHITECTURE.md).

## End-to-end acceptance

Two suites, one `mvn test`, against a running app + infra. See
[`e2etests/README.md`](../e2etests/README.md) for the full guide (prerequisites, the memory-bounded app
launch, the truncate-before-run gotcha, environments).

```bash
cd e2etests
JAVA_HOME=/path/to/jdk-21 mvn test                    # full: Karate REST + JDK-WebSocket suite
JAVA_HOME=/path/to/jdk-21 mvn test -De2e.groups=smoke # WS smoke subset
JAVA_HOME=/path/to/jdk-21 mvn test -Dkarate.tags=@smoke   # Karate smoke
JAVA_HOME=/path/to/jdk-21 mvn test -Ppreprod          # both suites → preprod host
```

**Business scenarios covered** — send / receive / retry (offline→reconnect redelivery) / delivery-ACK /
read receipts (`SENT→DELIVERED→READ`) / typing; presence; anti-spoof + mis-delivery; **block stops
delivery** (+ unblock resumes); **must-arrive → dead-letter → delivery on reconnect**; persistence +
cursor-paginated history; chat list / delete-for-me / block-unblock / admin console; WebRTC signaling;
system notices; and the **full attachment flow** (upload-url → PUT to storage → confirm → delivery as a
chat message → download, plus 400/401/403/409/413 + blocked-pair edges).

WebSocket scenarios use a JUnit suite over the JDK's `java.net.http.WebSocket` (Karate 2.x paywalls
WebSocket; 1.4.1's GraalVM JS handler can't build on this JDK — ADR-015).

## Load testing

Gatling harness in [`loadtest/`](../loadtest). Each virtual user drives the full inbound pipeline +
persistence + delivery + Tetris. Against a running (memory-bounded) app:

```bash
cd loadtest && JAVA_HOME=/path/to/jdk-21 mvn gatling:test \
  -Dgatling.simulationClass=load.MessengerWaveSimulation \
  -Dload.pool=1170 -Dload.activeFull=40 -Dload.waveSec=140   # 3 waves, ~4000 msg/s peak
```

Method, results (peak ~4000 msg/s, p99 sub-ms, bounded memory), and the reproduce recipe are in
[PERFORMANCE](PERFORMANCE.md). Watch the server side live via `/q/metrics` (see [OBSERVABILITY](OBSERVABILITY.md)).

## Coverage note

Coverage is driven by inspecting logic + business scenarios, **not** by chasing a JaCoCo number: under
Quarkus the surefire JaCoCo agent misses plain-unit-test coverage of app classes (they load via the
Quarkus classloader), so it reports 0% for classes that *are* tested. Treat the JaCoCo % as unreliable
here until that integration is fixed.
