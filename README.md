# Event Gateway (event-service)

Public-facing gateway of the **Event Ledger** system. It receives transaction events, validates
them, enforces idempotency, stores each event, and calls the internal **Account Service** to apply
the transaction. It also proxies balance queries to the Account Service.

> Companion service: **account-service** (separate repo) — the internal service that owns balances
> and transaction history. The Gateway talks to it over HTTP.
>
> For the architecture and the rationale behind every choice, see **[DESIGN_DECISIONS.md](DESIGN_DECISIONS.md)**.

---

## 1. Prerequisites

- **JDK 21 or later** (the project targets Java 21).
- **Git**.
- Maven is **not** required — the Maven Wrapper (`mvnw` / `mvnw.cmd`) is bundled.
- For end-to-end behaviour, run the **account-service** too (on port `8081`).

Check Java:
```bash
java -version   # should report 21+
```

## 2. Build & run the app

```bash
# 1. Clone and navigate INTO the project folder
git clone https://github.com/bharath51094/event-service.git
cd event-service

# 2. Build
./mvnw clean package          # Windows: mvnw.cmd clean package

# 3. Run (starts on http://localhost:8080)
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

**Start order:** start **account-service first** (port `8081`), then this Gateway. The Gateway
forwards each event to the Account Service; if that service is down, `POST /events` returns
`503` (see Resiliency). The Account Service location is configurable:

```properties
account-service.base-url=http://localhost:8081   # default
```

## 3. Running the tests

```bash
./mvnw test                   # Windows: mvnw.cmd test
```

## 4. H2 (in-memory database)

This service uses an **in-memory H2 database** — data exists only while the app is running and is
**wiped on restart**. To inspect the stored events, open the H2 console **while the app is running**:

1. Browse to `http://localhost:8080/h2-console`.
2. Log in with:
   - **JDBC URL:** `jdbc:h2:mem:eventdb`
   - **User Name:** `sa`
   - **Password:** *(leave blank)*
   - **Driver Class:** `org.h2.Driver`
3. Run a query, e.g.:

```sql
SELECT * FROM EVENTS;
SELECT * FROM EVENTS WHERE ACCOUNT_ID = 'acct-123' ORDER BY EVENT_TIMESTAMP;
```

## 5. Configuration & observability

| Concern | Detail |
|---|---|
| HTTP port | `8080` (`server.port`) |
| Account Service URL | `account-service.base-url` (default `http://localhost:8081`) |
| Database | in-memory H2 `jdbc:h2:mem:eventdb` |
| Logs | **structured JSON** (ECS) on the console — each line has `@timestamp`, `log.level`, `service.name`, `traceId`, `message` |
| Tracing | every request gets a trace id (from the `X-Trace-Id` header if supplied, else generated). It is logged on every line, **propagated to the Account Service** via `X-Trace-Id`, and echoed on the response header — so one request is traceable across both services |
| Health | `GET /health` (Spring Boot Actuator) — reports `UP`/`DOWN` plus DB connectivity |
| Metrics | `GET /metrics` and `GET /prometheus` (Actuator + Micrometer). Built-in `http_server_requests_*` give request count / error rate / latency histogram per endpoint for free; `resilience4j_circuitbreaker_*` expose breaker state. Plus a **custom domain metric** `ledger_events_processed_total{type,outcome}` — see below |

**Custom metric — `ledger_events_processed_total`:** a Micrometer `Counter` tagged `type`
(`CREDIT`/`DEBIT`) and `outcome` (`created` / `duplicate` / `rejected` / `unavailable`). It captures
what Actuator's HTTP metrics cannot: the **idempotency-replay rate** (created vs duplicate) and the
**business reason** for failures rather than just the HTTP status. It is recorded by a **Spring AOP
aspect** (`EventOutcomeMetricsAspect`), so the service layer stays free of instrumentation (observability
as a cross-cutting concern). The aspect binds to a `@TrackEventOutcome` marker annotation on the method
rather than its name, so renaming the method can't silently detach the metric.

### Resiliency (the Gateway's call to the Account Service)

- **Timeout** on the HTTP client (2s connect / 3s read) so a slow Account Service can't hang the Gateway.
- **Circuit breaker** (Resilience4j) on the forwarding and balance-proxy calls: once the Account
  Service is failing repeatedly, the breaker **opens** and the Gateway fails fast with a clear
  `503` instead of paying the timeout on every call (and stops piling requests onto a dead
  dependency). Business rejections (4xx, e.g. currency mismatch) are configured **not** to trip it.
- **Graceful degradation:** with the Account Service down, `POST /events` and balance queries
  return `503`, while `GET /events/{id}` and `GET /events?account=` keep working (they only need
  the Gateway's local data).

*Why a circuit breaker:* it's the handout's first listed pattern, it complements graceful
degradation (fast, meaningful errors when the dependency is clearly down), and it protects the
Gateway's request threads. It is layered on top of the timeout.

## 6. Endpoints

Event payload (`POST /events`):
```json
{
  "eventId": "evt-001",
  "accountId": "acct-123",
  "type": "CREDIT",
  "amount": 150.00,
  "currency": "USD",
  "eventTimestamp": "2026-05-15T14:02:11Z",
  "metadata": { "source": "mainframe-batch", "batchId": "B-9042" }
}
```

| Method | Endpoint | Description                             | Success | Notes |
|---|---|-----------------------------------------|---|---|
| `POST` | `/events` | Submit a transaction event              | `201` new / `200` duplicate | Idempotent on `eventId`; `503` if Account Service is down |
| `GET` | `/events/{id}` | Get a single event by `eventId`         | `200` | `404` if not found |
| `GET` | `/events?account={accountId}` | List an account's events, chronological | `200` | ordered by `eventTimestamp` |
| `GET` | `/accounts/{accountId}/balance` | Balance (proxied to Account Service)    | `200` | `404` unknown account; `503` if Account Service down |
| `GET` | `/health` | Health check                            | `200` | Actuator, includes DB status |
| `GET` | `/metrics`, `/prometheus` | Metrics                                 | `200` | Actuator + Micrometer; includes custom `ledger_events_processed_total` |
| `GET` | `/eventoutcomes` | Human-readable custom metric breakdown  | `200` | Per `type`+`outcome` count of `ledger_events_processed_total` with a plain-English description each |

Examples:
```bash
# Submit an event (optionally pass your own trace id)
curl -i -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" -H "X-Trace-Id: demo-123" \
  -d '{"eventId":"evt-001","accountId":"acct-123","type":"CREDIT","amount":150.00,"currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}'

# Read it back
curl http://localhost:8080/events/evt-001

# List an account's events (chronological)
curl "http://localhost:8080/events?account=acct-123"

# Balance (via the Gateway, proxied to the Account Service)
curl http://localhost:8080/accounts/acct-123/balance

# Health
curl http://localhost:8080/health

# Metrics (Prometheus scrape format) — grep the custom domain metric
curl -s http://localhost:8080/prometheus | grep ledger_events_processed_total
```
