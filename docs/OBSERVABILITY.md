# Observability

How to see what the Messenger is doing in production: **metrics**, **logs**, and (planned) **traces**.
All three are exposed by the running app — metrics at `/q/metrics` (Prometheus), health at `/q/health`,
logs to console + a rotating file.

> **Naming convention.** Every application metric lives under the dotted `messenger.*` namespace, which the
> Micrometer→Prometheus registry renders as `messenger_*` with the standard suffixes: `_total` for counters,
> `_seconds` for timers (`_count`/`_sum`/`_max`), and `_count`/`_sum`/`_max` for distribution summaries.
> Infrastructure metrics keep their Quarkus/Micrometer names (`jvm_*`, `sql_pool_*`, `redis_pool_*`,
> `http_server_*`).

---

## 1. Metrics

### Application metrics (`messenger_*`)

| Prometheus name | Type | Meaning |
|---|---|---|
| **Inbound (WS ingress → pipeline)** | | |
| `messenger_inbound_published_total` | counter | Frames accepted into the pipeline |
| `messenger_inbound_dropped_total` | counter | Frames dropped at the queue gate (sender gets an `overloaded` notice) |
| `messenger_inbound_failed_total` | counter | Frames that failed processing |
| `messenger_inbound_queue_size` | summary | Inbound backpressure queue depth, sampled per frame (`_count`/`_sum`/`_max`) |
| `messenger_inbound_processing_seconds` | timer | Inbound processing latency |
| `messenger_credits_exhausted_total` | counter | Frames rejected by the per-connection **credit** gate (flood/abuse guard) |
| **Outbound (delivery)** | | |
| `messenger_outbound_published_total` | counter | Messages delivered **live** to a connected recipient |
| `messenger_outbound_dropped_total` | counter | Outbound frames dropped at the queue gate |
| `messenger_outbound_failed_total` | counter | Deliveries that failed (recipient marked offline; message held in outbox) |
| `messenger_outbound_queue_size` | summary | Outbound backpressure queue depth (`_count`/`_sum`/`_max`) |
| `messenger_outbound_processing_seconds` | timer | Outbound processing latency |
| **Persist group-commit batcher** | | |
| `messenger_persist_batch_flushes_total` | counter | Batch transactions committed |
| `messenger_persist_batch_shed_total` | counter | Items shed when batches outran the sink (overload) |
| `messenger_persist_batch_fallbacks_total` | counter | Messages retried individually after a batch-tx failure (poison-row isolation) |
| `messenger_persist_batch_drain_timeout_total` | counter | Graceful-shutdown drains that hit the timeout with work in flight (→ redelivery) |
| `messenger_persist_batch_size` | summary | Items coalesced into one batch (`_count`/`_sum`/`_max`) |
| `messenger_persist_batch_flush_seconds` | timer | Latency of one batch flush |
| **Read-receipt group-commit batcher** | | |
| `messenger_read_batch_flushes_total` | counter | READ-mark batch transactions committed |
| `messenger_read_batch_shed_total` | counter | READ marks shed under overload (resolve to 0; receipts are re-sent) |
| `messenger_read_batch_drain_timeout_total` | counter | Drain timeouts at shutdown |
| `messenger_read_batch_size` | summary | Marks per batch (`_count`/`_sum`/`_max`) |
| `messenger_read_batch_flush_seconds` | timer | READ-batch flush latency |
| **Outbox poller (delivery pump)** | | |
| `messenger_poller_polls_total` | counter | Poll cycles that fetched & dispatched a batch |
| `messenger_poller_skipped_total` | counter | Cycles skipped because the outbound queue was still draining |
| `messenger_poller_errors_total` | counter | Errors during a poll cycle |
| `messenger_poller_poll_seconds` | timer | Duration of one poll+dispatch cycle |
| `messenger_poller_delay_milliseconds` | gauge | Current regulated inter-poll delay |
| `messenger_poller_last_batch_size` | gauge | Rows fetched in the most recent batch |
| **Sessions** | | |
| `messenger_sessions_registered` | gauge | Currently online WebSocket client sessions |

### Infrastructure metrics to watch (Quarkus/Micrometer built-ins)

| Name | Why it matters |
|---|---|
| `sql_pool_queue_delay_seconds_max` / `_pending` (tagged by `pool.name`) | DB pool wait. Two pools since Phase 5b: the **default** (persist) and **`poller`** (delivery/GC) — compare them to see contention. |
| `redis_pool_queue_size`, `redis_pool_queue_delay_seconds_max` | Redis pool pressure (presence/idempotency/Tetris under disconnect storms). |
| `jvm_memory_used_bytes{area="heap"}`, `jvm_gc_pause_seconds_*` | Heap working set & GC — should be flat (no leak) under a soak. |
| `http_server_requests_seconds_*`, `http_server_websocket_connections_seconds_active_count` | REST latency & live WS connection count. |

### Dashboard

[`docs/ops/grafana-messenger-dashboard.json`](ops/grafana-messenger-dashboard.json) — an 11-panel Grafana
dashboard over these series (ingest/delivery rates, backpressure shed, flush latency, queue depth, pool
wait, heap, GC, credits/sessions, poller health). Import into Grafana with a Prometheus datasource scraping
`/q/metrics`.

---

## 2. Logs

Console + a **rotating file handler** (`quarkus.log.file`): 20 MB × 10 files, date-suffixed,
rotate-on-boot. Path via `LOG_FILE` (default relative `messenger.log`; deploys set
`/var/log/messenger/messenger.log`). Format:
`%d{...} %-5p [%c{2.}] (%t) %X %s%e%n` — timestamp, level, abbreviated logger, thread, **MDC**, message.

### Level convention

| Level | Used for | Examples |
|---|---|---|
| `ERROR` | Genuine server faults only | unexpected exceptions, tx rollback, terminal stream death (retries exhausted) |
| `WARN` | Expected-but-notable / client-caused | credit exhaustion, invalid client message, delivery to a now-offline recipient, config warnings |
| `INFO` | Lifecycle | startup, batcher ready, graceful shutdown & drain |
| `DEBUG` | Per-message trace (off in prod by default) | frame received, delivered, "no active sessions", best-effort live-send failure |

> A best-effort live send that loses a closed-socket race, or a delivery to an offline recipient, is **not**
> an error — durability is the outbox's job and the message is redelivered. These are DEBUG/WARN, never ERROR,
> so a large offline backlog can't flood the error log.

### Correlation (MDC)

| MDC key | Set where | Notes |
|---|---|---|
| `userId` | REST (`MdcRequestFilter`, from `X-User-Id`) + WS ingress (authenticated sender) | who |
| `requestId` | REST — minted per request, echoed back as the `X-Request-Id` response header | client-side correlation |
| `msgId` | WS ingress — the frame's message id | which message |

MDC propagates across the reactive REST endpoint via **`quarkus-smallrye-context-propagation`**, and is
stamped at the WS ingress boundary for that frame's synchronous log lines. **Limitation:** it is *not* carried
through the shared router `Multi` (the pipeline stages run on a shared subscription, not a per-message chain),
so stage-level logs don't inherit `msgId`. Stage logs that need it include the id explicitly. Full per-message
propagation through the pipeline would require distributed tracing (below).

---

## 3. Traces

**Current state:** no distributed tracing. Correlation is via the MDC ids above — sufficient to tie together
one REST request or one ingress hop, but not to follow a message end-to-end across the async pipeline +
outbox + delivery.

**Planned (deferred until there's a collector):** add `quarkus-opentelemetry` with an OTLP exporter pointed
at a Jaeger/Tempo collector. Quarkus auto-instruments REST, reactive PostgreSQL, Kafka and Redis; the WS
ingress and the router stages would need manual spans (a span per message, child spans per stage) to get a
true end-to-end trace. This instruments every hop on the hot path, so it is a **working-path change** to be
introduced with sign-off and measured for overhead — not switched on blindly, and pointless without a
collector to receive the spans.

---

## 4. How to inspect

```bash
# Metrics (Prometheus text)
curl -s localhost:8080/q/metrics | grep '^messenger_'

# Health (readiness/liveness)
curl -s localhost:8080/q/health

# Live logs (file handler)
tail -f "${LOG_FILE:-messenger.log}"
```

Load-test harness and the throughput/latency numbers these metrics produce under load are in
[`PERFORMANCE.md`](PERFORMANCE.md).
