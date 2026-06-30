# Event Gateway (event-service)

The public-facing service of the **Event Ledger** system. It receives transaction events, validates
them, enforces idempotency, stores each event, and calls the internal **Account Service** to apply the
transaction. It also proxies balance queries to the Account Service.

---

## 1. Architecture

```
Client ──HTTP──▶ Event Gateway (:8080) ──HTTP (sync)──▶ Account Service (:8081)
                  H2: eventdb                             H2: accountdb
```

- The **Gateway** is the only public entry point. It validates input, enforces idempotency by `eventId`,
  stores each event, and forwards it to the Account Service to apply the balance change.
- The **Account Service** (separate repo) owns balances and transaction history. Only the Gateway calls
  it — every call carries a shared `X-Internal-Api-Key`.
- Each service has its **own in-memory H2 database**. They share no database and no in-process state.
- Calls between them are **synchronous REST**. The Gateway generates a trace id per request and
  propagates it as `X-Trace-Id`, so one request is traceable across both services.

## 2. Prerequisites

- **JDK 21+** (`java -version` should report 21 or later).
- Maven is **not** required — the Maven Wrapper (`mvnw` / `mvnw.cmd`) is bundled.
- Run the **account-service** too (port `8081`) for end-to-end behaviour.

## 3. Run (both services)

Start the **account-service first** (port `8081`), then this Gateway (port `8080`). There is no Docker —
run both manually.

```bash
# 1. account-service (in its own repo) — start it first on :8081
#    see the account-service README

# 2. this Gateway
git clone https://github.com/bharath51094/event-service.git
cd event-service
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run  → http://localhost:8080
```

The Account Service URL is configurable (default shown):

```properties
account-service.base-url=http://localhost:8081
```

## 4. Run the tests

```bash
./mvnw test                   # Windows: mvnw.cmd test
```

## 5. Endpoints

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

| Method | Endpoint | Description                            | Success | Notes |
|---|---|----------------------------------------|---|---|
| `POST` | `/events` | Submit a transaction event             | `201` new / `200` duplicate | Idempotent on `eventId`; `503` if Account Service is down |
| `GET` | `/events/{id}` | Get one event by `eventId`             | `200` | `404` if not found |
| `GET` | `/events?account={accountId}` | List an account's events               | `200` | ordered by `eventTimestamp` |
| `GET` | `/accounts/{accountId}/balance` | Balance (proxied to Account Service)   | `200` | `404` unknown account; `503` if Account Service down |
| `GET` | `/health` | Health check                           | `200` | Actuator; includes DB status |
| `GET` | `/metrics`, `/prometheus` | Metrics                                | `200` | Actuator + Micrometer |
| `GET` | `/eventoutcomes` | Human-readable custom metric breakdown | `200` | Per `type`+`outcome` counts with a description |

```bash
# Submit an event (you may pass your own trace id)
curl -i -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" -H "X-Trace-Id: demo-123" \
  -d '{"eventId":"evt-001","accountId":"acct-123","type":"CREDIT","amount":150.00,"currency":"USD","eventTimestamp":"2026-05-15T14:02:11Z"}'

curl http://localhost:8080/events/evt-001
curl "http://localhost:8080/events?account=acct-123"
curl http://localhost:8080/accounts/acct-123/balance
```

## 6. Resiliency

The Gateway protects its calls to the Account Service with:

- **Timeout** — 2s connect / 3s read, so a slow dependency can't hang the Gateway.
- **Circuit breaker** (Resilience4j) — after repeated failures the breaker **opens** and the Gateway fails
  fast with `503` instead of paying the timeout on every call. Business `4xx` (e.g. currency mismatch) do
  **not** trip it.
- **Graceful degradation** — when the Account Service is down, `POST /events` and balance queries return
  `503`, while `GET /events/{id}` and `GET /events?account=` keep working (they use only local data).

**Why a circuit breaker:** it is the handout's first listed pattern, it gives fast and meaningful errors
when the dependency is clearly down, and it protects the Gateway's request threads. It sits on top of the
timeout.

## 7. Observability

| What                                                      | How to view |
|-----------------------------------------------------------|---|
| **Health** (+ DB connectivity)                            | `curl http://localhost:8080/health` |
| **Structured logs**                                       | ECS JSON on the console — each line has `@timestamp`, `log.level`, `service.name`, `traceId`, `message` |
| **Tracing**                                               | the `traceId` above is shared with the Account Service for the same request (`X-Trace-Id`) |
| **All metrics**                                           | `curl http://localhost:8080/prometheus` |
| **HTTP metrics** (count / errors / latency, per endpoint) | `curl -s http://localhost:8080/prometheus \| grep http_server_requests` |
| **Custom domain metric**                                  | `curl -s http://localhost:8080/prometheus \| grep ledger_events_processed_total` |
| **Resilience (circuit breaker)**                          | `curl -s http://localhost:8080/prometheus \| grep resilience4j_circuitbreaker` |
| **Custom Readable domain metric breakdown**               | `curl http://localhost:8080/eventoutcomes` |

**Custom metric — `ledger_events_processed_total{type,outcome}`:** a Micrometer counter tagged `type`
(`CREDIT`/`DEBIT`) and `outcome` (`created` / `duplicate` / `rejected` / `unavailable`). It shows what the
HTTP metrics cannot: the idempotency-replay rate (created vs duplicate) and the business reason for a
failure. It is recorded by a Spring AOP aspect, so the service code stays free of instrumentation.
`/eventoutcomes` renders the same data with a plain-English sentence per row.

**Public access:** the Gateway is public-facing, so it has no inbound auth — `/health`, `/metrics`,
`/prometheus`, and `/eventoutcomes` are all open. This is convenient for the take-home. In production, we 
would move Actuator to a separate management port and/or restrict `/metrics` + `/prometheus` to an internal
scrape network, and disable the H2 console.

## 8. Internal authentication (to the Account Service)

The Account Service is internal. The Gateway authenticates to it by sending a shared secret header
`X-Internal-Api-Key` on every call (value `account-service.api-key` in `application.yaml`, matching the
Account Service's `internal.api-key`).

## 9. H2 console (inspect stored events)

While the app runs, open `http://localhost:8080/h2-console`:
- **JDBC URL:** `jdbc:h2:mem:eventdb`  •  **User:** `sa`  •  **Password:** *(blank)*

```sql
SELECT * FROM EVENTS;
SELECT * FROM EVENTS WHERE ACCOUNT_ID = 'acct-123' ORDER BY EVENT_TIMESTAMP;
```
Data is in-memory and is wiped on restart.
