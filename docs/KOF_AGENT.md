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

## What a bulletin looks like

Everything collected during one flush window (10 s) goes out as **one** message:

```
📡 API Énóis · Produção · PRD
2026-11-05 12:30:40 a 12:30:50
🟠 ERROR 2 · 🟡 WARN 1

🟠 ERROR 12:30:45 · HTTP 500 ×2 (até 12:30:47)
GET /stats/community · status 500
IP 203.0.113.10, 198.51.100.4 · user 42 · req 61244fe7da24
IllegalStateException em StatsService.java:37
IllegalStateException: ...
<short stack: each cause plus up to three application frames>

🟡 WARN 12:30:41 · Estado da aplicação
com.socialcoisa.rede.shared.security.PanicModeService: panic_mode changed active=true

Requisições 1230 (4xx 12 · 5xx 2) · 2 5xx já contados nas exceções
```

Severity icons: 🔵 TRACE, ⚪ DEBUG, 🟢 INFO, 🟡 WARN, 🟠 ERROR, 🔴 FATAL. Bulletins without ERROR,
FATAL or health entries are delivered silently (no notification sound). A bulletin longer than
Telegram's limit is split into numbered parts. Quiet windows send nothing.

## What is listed

| source | listed when |
|---|---|
| application log lines (`MonitoringLogAppender`) | level in `KOF_AGENT_LEVELS` (default WARN, ERROR, FATAL) |
| library WARN/ERROR (Hikari, Hibernate, Flyway, scheduler, Tomcat) | same; this is where database and infrastructure failures show up |
| unhandled exceptions (`GlobalExceptionHandler`) | always, with error class, origin (`File.java:line`) and a short stack |
| HTTP 5xx (500, 502, 503, 504...) | always; merged into the exception entry when the exception already reported that request |
| slow requests (over `KOF_AGENT_SLOW_REQUEST`, default 2 s) | as WARN |
| startup / shutdown (`app_started`, `app_stopping`) | always |
| health transitions (`HealthWatcher`: db, disk...) | always; going DOWN is FATAL, recovering is INFO |
| admin audit (`audit` logger) and application state (panic mode...) | always |
| threads dying from an uncaught exception | as ERROR (`UncaughtExceptionReporter`) |
| successful and 4xx requests | never listed, only counted in the footer |

FATAL comes from the classifier: `VirtualMachineError` (out of memory...), the database unreachable
(no connection could be obtained), a health component DOWN, or a log line with the SLF4J marker
`FATAL`.

Each entry carries the client IP and user id of the request that triggered it (maintainers' decision
for this private channel). Message text and stacks go through `SensitiveDataRedactor` first:
passwords, tokens, cookies, `Authorization` values, JWTs, private keys, card numbers, CPF/CNPJ,
e-mails, phone numbers and IPs inside free text are removed. Request bodies and headers are never
collected at all.

## Reliability

- **Never blocks the app.** Producers only offer the event to a bounded in-memory queue
  (`KOF_AGENT_QUEUE_CAPACITY`). When it is full, ERROR/FATAL go to a reserve queue and the rest is
  counted; the next bulletin prints how many were dropped.
- **One dispatcher thread** does classification, de-duplication (by event id), grouping of repeats
  (same fingerprint in a window becomes one entry with `×N`), formatting and sending.
- **Rate limit**: at most `KOF_AGENT_RATE_PER_MINUTE` messages per minute (default 18, Telegram
  allows about 20 per channel), and Telegram's own `retry_after` on HTTP 429 is honored.
- **Retry** with exponential backoff and jitter (`KOF_AGENT_RETRY_*`).
- **Circuit breaker**: after `KOF_AGENT_CIRCUIT_FAILURE_THRESHOLD` consecutive failures no attempt
  is made for `KOF_AGENT_CIRCUIT_OPEN_DURATION` (doubling up to the max); then one probe.
- **Critical bulletins persist.** A bulletin with an ERROR, FATAL, lifecycle or health entry is
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

- `rede.kof_agent.events{outcome=accepted|duplicate|grouped|counted_only|dropped}`
- `rede.kof_agent.messages{outcome=bulletin|sent|failed|replayed|dead_letter}`
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
| `KOF_AGENT_SERVICE_NAME` | `API Énóis` | header of every bulletin |
| `KOF_AGENT_LEVELS` | `WARN,ERROR,FATAL` | log levels listed (add `INFO`, `DEBUG`, `TRACE` to see more) |
| `KOF_AGENT_FLUSH_INTERVAL` | `10s` | bulletin cadence |
| `KOF_AGENT_BATCH_MAX_EVENTS` | `40` | distinct entries per bulletin; the rest is counted |
| `KOF_AGENT_RATE_PER_MINUTE` / `KOF_AGENT_RATE_BURST` | `18` / `3` | send rate limit |
| `KOF_AGENT_RETRY_MAX_ATTEMPTS` / `_INITIAL_BACKOFF` / `_MAX_BACKOFF` | `6` / `1s` / `5m` | retry policy |
| `KOF_AGENT_CIRCUIT_FAILURE_THRESHOLD` / `_OPEN_DURATION` / `_MAX_OPEN_DURATION` | `5` / `30s` / `10m` | circuit breaker |
| `KOF_AGENT_QUEUE_CAPACITY` | `10000` | in-memory queue |
| `KOF_AGENT_SPOOL_DIR` / `KOF_AGENT_SPOOL_MAX_FILES` | `<monitoring dir>/kof-agent-spool` / `10000` | disk spool |
| `KOF_AGENT_SLOW_REQUEST` | `2s` | slow request threshold |
| `KOF_AGENT_ZONE` | `America/Sao_Paulo` | timestamps |
| `KOF_AGENT_API_BASE_URL` | `https://api.telegram.org` | Bot API base URL |
| `KOF_AGENT_REQUEST_TIMEOUT` / `KOF_AGENT_SHUTDOWN_TIMEOUT` | `10s` / `5s` | timeouts |
| `REDE_MONITORING_HEALTH_WATCH_ENABLED` / `_INTERVAL` | `true` / `60s` | health transition events |

## Code

`ops/monitoring`: `MonitoringService` (fan-out to listeners), `MonitoringLogAppender` (log capture),
`HealthWatcher`, `SensitiveDataRedactor`. `ops/monitoring/kofagent`: `KofAgentDispatcher` (queue,
window, sending), `AlertClassifier`, `AlertFormatter`, `TelegramBotTransport`, `AlertSpool`,
`CircuitBreaker`, `TokenBucket`, `KofAgentConfiguration` (lifecycle and metrics).
