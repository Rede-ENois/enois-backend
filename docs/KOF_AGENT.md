# Observability channel on Telegram (Kof Agent)

The backend sends its important operational events to a private Telegram channel through the
**Kof Agent** bot (Telegram Bot API). It is one more destination of the existing monitoring
pipeline, not a second logging system: whatever reaches `MonitoringService` (the admin panel's
source) also reaches the channel.

## Where it is active

| environment | token | result |
|---|---|---|
| production (`prd`) | GitHub secrets `KOF_AGENT_API_TOKEN` + `KOF_AGENT_CHAT_ID`, injected only into the production stack by `build-and-push.yml` | active |
| QA, HML, dev, tests | no token | inert: no thread, no listener, nothing sent |

The channel is active only when **both** `KOF_AGENT_API_TOKEN` and `KOF_AGENT_CHAT_ID` are set
(and `KOF_AGENT_ENABLED` is not `false`). Every message carries the environment in its header, so a
test run in another environment with its own chat can never be confused with production.

`KOF_AGENT_CHAT_ID` is the **numeric** channel id (`-100...`), not the invite link: the Bot API cannot
resolve invite links. To get it, open the channel on Telegram Web (`web.telegram.org/a/`): the URL
ends with `#-100...`. The bot must be an **administrator** of the channel with permission to post.

## What reaches the channel

The channel is built to be read, not scrolled: few messages, each one meaning something. There are
three kinds.

### 1. Alerts: a new kind of failure

A kind of failure is its fingerprint: severity, category, endpoint, status, error class, origin
(`File.java:line`) and the normalized message. At the notify levels (`KOF_AGENT_NOTIFY_LEVELS`,
default ERROR and FATAL) a kind is alerted **the first time it shows up**, and again only when it comes
back after `KOF_AGENT_INCIDENT_QUIET` (30 min) without occurrences. Repeats in between are not
alerted; they are counted in the digest. The same deadlock every few seconds is one alert; the same
deadlock again the next morning is a new one. The memory of known kinds is in-process, so the first
occurrence after a restart or deploy is alerted again.

New alerts of one flush window (10 s) go out together:

```
📡 API Énóis · Produção · PRD
2026-11-05 15:40:01 a 15:40:11
🟠 ERROR 3

🟠 ERROR 15:40:01 · HTTP 500 · Banco de dados ×3 (até 15:40:11)
POST /feed/posts/{postId}/reactions · status 500
IP 203.0.113.10, 198.51.100.4 · user user_42 · req 61244fe7da24
JpaSystemException em ReactionService.java:88
JpaSystemException: could not execute statement [...]
<short stack: each cause plus up to three application frames>
```

Events that change the picture are alerted **every time**, whatever their level and without the
incident memory: startup and shutdown (`app_started`, `app_stopping`), health transitions
(`HealthWatcher`: going DOWN is FATAL, recovering is INFO), admin audit (`audit` logger) and
application state (panic mode, maintenance, feature flags).

Alerts without ERROR, FATAL or health entries are delivered silently (no notification sound). An
alert longer than Telegram's limit is split into numbered parts.

### 2. Latency: the API became slow, or recovered

A single slow request (over `KOF_AGENT_SLOW_REQUEST`, 2 s) is never an alert. Requests are counted in
windows of `KOF_AGENT_LATENCY_WINDOW` (5 min); when a window with at least
`KOF_AGENT_LATENCY_MIN_REQUESTS` (100) requests has `KOF_AGENT_LATENCY_SLOW_RATIO` (10%) or more of
them slow, the API is slow and one WARN alert goes out with the routes that weighed most. One INFO
message (silent) goes out when a window is back under the ratio. Nothing in between. A window with too
little traffic to judge keeps the current state.

```
📡 API Énóis · Produção · PRD

🟡 WARN 12:05:00 · API lenta · Desempenho
20,0% das requisições acima de 2,0 s entre 12:00 e 12:05 (30 de 150)
Mais lentas: POST /feed/posts ×30
```

### 3. Digest: what happened in the period

Every `KOF_AGENT_DIGEST_INTERVAL` (1 h, aligned to the clock: 13:00, 14:00...), silently, and **only
when the period had something at `KOF_AGENT_DIGEST_MIN_LEVEL` (WARN by default): a failure kind at
that severity or above, a 5xx, or the API entering the slow state**. An hour with only lower-severity
noise (4xx, a few slow requests on a healthy API) sends nothing. On shutdown the running period is
sent as a partial digest under the same rule.

```
📊 API Énóis · Produção · PRD · resumo
2026-11-05 14:00 a 15:00
Requisições 98123 · 4xx 120 · 5xx 80 · lentas 1203 (1,2%)

Falhas (3 tipos)
🟠 ×23 · HTTP 500 · Banco de dados · POST /feed/posts/{postId}/reactions
JpaSystemException: could not execute statement [...]
🟡 ×2 · HTTP 503 · Exceção · POST /groups/{groupId}/calls
ApiException: ...

Rotas lentas (acima de 2,0 s)
POST /feed/posts/{postId}/reactions ×983 · mediana 2,6 s · máx 66,3 s
```

"Falhas" lists every kind at the collected levels (`KOF_AGENT_LEVELS`, default WARN and above), alerted
or not, with its count in the period; the 8 most severe and frequent are shown, the rest is counted.
"Rotas lentas" shows the 8 routes with the most slow requests.

### Severity

Icons: 🔵 TRACE, ⚪ DEBUG, 🟢 INFO, 🟡 WARN, 🟠 ERROR, 🔴 FATAL.

| source | severity | alerted | in the digest |
|---|---|---|---|
| application and library log lines (`MonitoringLogAppender`: Hikari, Hibernate, Flyway, scheduler...) | the log level | at notify levels, once per kind | at collected levels |
| unhandled exceptions (`GlobalExceptionHandler`) | ERROR for 5xx, with class, origin and a short stack | once per kind | yes |
| a 5xx the application raised on purpose (`ApiException` without a cause: feature not configured, provider reported unavailable) | WARN | no | yes |
| HTTP 5xx without an exception | ERROR | once per kind | yes |
| HTTP 5xx already reported by its exception | merged into the exception | | |
| slow requests | | only as the latency state | per route |
| successful and 4xx requests | | no | counted |
| startup / shutdown, health transitions, admin audit, application state | as emitted | always | no |
| threads dying from an uncaught exception (`UncaughtExceptionReporter`) | ERROR | once per kind | yes |

FATAL comes from the classifier: `VirtualMachineError` (out of memory...), the database unreachable
(no connection could be obtained), a health component DOWN, or a log line with the SLF4J marker
`FATAL`.

Each alert carries the client IP and user id of the requests that triggered it (maintainers' decision
for this private channel); the digest carries neither. Message text and stacks go through
`SensitiveDataRedactor` first: passwords, tokens, cookies, `Authorization` values, JWTs, private keys,
card numbers, CPF/CNPJ, e-mails, phone numbers and IPs inside free text are removed. Request bodies and
headers are never collected at all.

## Reliability

- **Never blocks the app.** Producers only offer the event to a bounded in-memory queue
  (`KOF_AGENT_QUEUE_CAPACITY`). When it is full, ERROR/FATAL go to a reserve queue and the rest is
  counted; the next bulletin prints how many were dropped.
- **One dispatcher thread** does classification, de-duplication (by event id), grouping of repeats
  (same fingerprint in a window becomes one entry with `×N`), the incident memory, the latency and
  digest counters, formatting and sending. All of that state is bounded.
- **Rate limit**: at most `KOF_AGENT_RATE_PER_MINUTE` messages per minute (default 18, Telegram
  allows about 20 per channel), and Telegram's own `retry_after` on HTTP 429 is honored.
- **Retry** with exponential backoff and jitter (`KOF_AGENT_RETRY_*`).
- **Circuit breaker**: after `KOF_AGENT_CIRCUIT_FAILURE_THRESHOLD` consecutive failures no attempt
  is made for `KOF_AGENT_CIRCUIT_OPEN_DURATION` (doubling up to the max); then one probe.
- **Critical alerts persist.** An alert with an ERROR, FATAL, lifecycle or health entry is
  written to `KOF_AGENT_SPOOL_DIR` before the first attempt and deleted only after Telegram confirms
  it. During an outage it waits on disk and is replayed oldest first when the channel works again,
  including after a restart. Bulletins Telegram refuses for good go to `dead/` inside the spool.
- **Shutdown** closes the current window and tries to deliver for `KOF_AGENT_SHUTDOWN_TIMEOUT`.
- If Telegram or the bot is unavailable, the backend keeps working; the outage is visible in the
  `rede.kof_agent.*` metrics and on the next bulletins ("canal: ...").

The spool lives on the container's filesystem (`/app/data/monitoring/kof-agent-spool`), so it
survives a crash or restart of the container but not a redeploy that recreates it. Mounting
`/app/data` as a volume on the production stack would extend that to redeploys.

## Monitoring the channel itself

Micrometer meters (exposed by `/actuator/prometheus` where enabled):

- `rede.kof_agent.events{outcome=accepted|duplicate|grouped|suppressed|counted_only|dropped}`
  (`suppressed`: repeat of a known failure kind, counted in the digest instead of alerted)
- `rede.kof_agent.messages{outcome=bulletin|digest|latency|sent|failed|replayed|dead_letter}`
- `rede.kof_agent.queue`, `rede.kof_agent.pending`, `rede.kof_agent.spool`, `rede.kof_agent.circuit_open`

Log lines (stdout only; they are never sent back to the channel): `kof_agent_ready`,
`kof_agent_channel_unreachable`, `kof_agent_circuit_open`, `kof_agent_circuit_closed`,
`kof_agent_rate_limited`, `kof_agent_retries_exhausted`, `kof_agent_replay`, `kof_agent_disabled`.

## Configuration

All under `rede.kof-agent.*` in `application.properties`; environment variables:

| variable | default | meaning |
|---|---|---|
| `KOF_AGENT_API_TOKEN` | (empty) | bot token; only from the environment/secret manager |
| `KOF_AGENT_CHAT_ID` | (empty) | numeric channel id |
| `KOF_AGENT_ENABLED` | `true` | kill switch |
| `KOF_AGENT_ENVIRONMENT` | `SPRING_PROFILES_ACTIVE` | `prd`, `qa`, `sandbox`, `hml`, `dev` |
| `KOF_AGENT_SERVICE_NAME` | `API Énóis` | header of every message |
| `KOF_AGENT_LEVELS` | `WARN,ERROR,FATAL` | levels collected into the digest |
| `KOF_AGENT_NOTIFY_LEVELS` | `ERROR,FATAL` | levels alerted the first time a kind of failure shows up |
| `KOF_AGENT_INCIDENT_QUIET` | `30m` | a kind quiet for this long is alerted again when it comes back |
| `KOF_AGENT_DIGEST_INTERVAL` | `1h` | digest cadence, aligned to the clock |
| `KOF_AGENT_DIGEST_MIN_LEVEL` | `WARN` | lowest severity a period needs for its digest to go out |
| `KOF_AGENT_LATENCY_WINDOW` / `_MIN_REQUESTS` / `_SLOW_RATIO` | `5m` / `100` / `0.10` | when the API counts as slow |
| `KOF_AGENT_FLUSH_INTERVAL` | `10s` | new alerts collected this long go out together |
| `KOF_AGENT_BATCH_MAX_EVENTS` | `40` | distinct entries per alert message; the rest is counted |
| `KOF_AGENT_RATE_PER_MINUTE` / `KOF_AGENT_RATE_BURST` | `18` / `3` | send rate limit |
| `KOF_AGENT_RETRY_MAX_ATTEMPTS` / `_INITIAL_BACKOFF` / `_MAX_BACKOFF` | `6` / `1s` / `5m` | retry policy |
| `KOF_AGENT_CIRCUIT_FAILURE_THRESHOLD` / `_OPEN_DURATION` / `_MAX_OPEN_DURATION` | `5` / `30s` / `10m` | circuit breaker |
| `KOF_AGENT_QUEUE_CAPACITY` | `10000` | in-memory queue |
| `KOF_AGENT_SPOOL_DIR` / `KOF_AGENT_SPOOL_MAX_FILES` | `<monitoring dir>/kof-agent-spool` / `10000` | disk spool |
| `KOF_AGENT_SLOW_REQUEST` | `2s` | slow request threshold (latency state and digest) |
| `KOF_AGENT_ZONE` | `America/Sao_Paulo` | timestamps |
| `KOF_AGENT_API_BASE_URL` | `https://api.telegram.org` | Bot API base URL |
| `KOF_AGENT_REQUEST_TIMEOUT` / `KOF_AGENT_SHUTDOWN_TIMEOUT` | `10s` / `5s` | timeouts |
| `REDE_MONITORING_HEALTH_WATCH_ENABLED` / `_INTERVAL` | `true` / `60s` | health transition events |

## Code

`ops/monitoring`: `MonitoringService` (fan-out to listeners), `MonitoringLogAppender` (log capture),
`HealthWatcher`, `SensitiveDataRedactor`. `ops/monitoring/kofagent`: `KofAgentDispatcher` (queue,
window, sending), `IncidentTracker`, `LatencyWatch`, `DigestCollector`, `AlertClassifier`, `AlertFormatter`, `TelegramBotTransport`, `AlertSpool`,
`CircuitBreaker`, `TokenBucket`, `KofAgentConfiguration` (lifecycle and metrics).
