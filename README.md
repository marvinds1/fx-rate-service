# fx-rate-service

A gRPC service that prices currency conversions and **locks the quoted rate** for a short time,
built with Java 17, Spring Boot, grpc-java and Redis. A client asks for a quote, shows it to the
customer, and redeems it when the customer confirms — at exactly the quoted amount, once.

![CI](https://github.com/marvinds1/fx-rate-service/actions/workflows/ci.yml/badge.svg)

It is the multi-currency building block for
[payment-ledger-service](https://github.com/marvinds1/payment-ledger-service): before booking a
cross-currency transfer, the ledger redeems a quote and books both legs at the locked rate.

## Highlights

| Concern | How it is handled |
|---|---|
| Rate lock | A quote is stored in Redis with a TTL (default 30s). The customer gets the price they saw, not the price at execution time. |
| Exactly-once redemption | Redeeming uses Redis `GETDEL`, which is atomic: of many concurrent redeems for one quote, exactly one succeeds (tested with 8 parallel calls). Expired or used quotes return `NOT_FOUND`. |
| Money math | `BigDecimal` with 16 significant digits for rates; amounts are integer minor units. Only the final amount is rounded, always **down** to the target currency's minor unit (respects 0-decimal currencies like JPY). |
| Cross rates | All rates are kept against USD; `IDR→SGD` is derived as `SGD/USD ÷ IDR/USD`, so N currencies need N rates, not N². |
| Spread | Configurable margin in basis points, returned on the quote alongside the mid and customer rate for transparency. |
| Contract-first API | Protobuf schema in [`src/main/proto`](src/main/proto/fx/v1/fx_service.proto), versioned package `fx.v1`. Errors use gRPC status codes (`INVALID_ARGUMENT`, `NOT_FOUND`) with readable descriptions. |
| Operability | Standard gRPC health service, server reflection (works with `grpcurl` / Postman), one access-log line per call (method, status, latency), graceful shutdown inside the Spring lifecycle. |

## Security, performance and concurrency

| Risk | Guard |
|---|---|
| Unauthenticated access | `x-api-key` metadata checked by a server interceptor when `FX_API_KEYS` is set (`client-id:secret` pairs, ≥16-char secrets, several per client for rotation), compared in constant time. Health is open; reflection can be switched off (`GRPC_REFLECTION_ENABLED=false`). |
| Quote theft / quote burning | Quotes are stored per client (`fx:{client}:quote:{id}`). Another client's redeem looks up a different key, so it can neither use nor consume someone else's quote. Quote ids must be UUIDs. |
| Double redemption | `GETDEL` is atomic: of 8 concurrent redeems exactly one succeeds (tested). |
| Retried `CreateQuote` | Optional `request_id`: a Lua script stores the request id and the quote together with `SET NX`, so concurrent retries all get the same quote (tested with 8 in parallel). Reusing an id with different parameters → `INVALID_ARGUMENT`. |
| Abuse / oversized calls | 16 KB message and 8 KB metadata limits (`RESOURCE_EXHAUSTED`), 100 concurrent calls per connection, per-quote limit of 1,000,000 USD equivalent, strict input validation. |
| Thread exhaustion | Handler pool is fixed size (`GRPC_THREADS`, default 32) instead of grpc-java's default unbounded cached pool. Idle connections are closed after 5 min and recycled after 30 min so load rebalances across replicas. |
| Slow or down Redis | 500 ms command timeout, 1 s connect timeout; failures map to `UNAVAILABLE` (retryable) instead of `INTERNAL`. Calls whose deadline already passed are not processed. |
| Redis Cluster | Keys use a `{client}` hash tag so the Lua script's keys always live in one slot. |

## Flow

```mermaid
sequenceDiagram
    participant App as Mobile app / Transfer service
    participant FX as fx-rate-service
    participant R as Redis

    App->>FX: CreateQuote(IDR → USD, 1,000,000.00)
    FX->>FX: mid = USD/IDR, customer = mid × (1 − spread)
    FX->>R: SET fx:quote:{id} … EX 30
    FX-->>App: Quote{id, 61.23 USD, rates, expires_at}
    Note over App: customer confirms within 30s
    App->>FX: RedeemQuote(id)
    FX->>R: GETDEL fx:quote:{id}
    R-->>FX: quote (or nil if expired / already used)
    FX-->>App: Quote  |  NOT_FOUND
```

## API

| RPC | Description |
|---|---|
| `CreateQuote(sell_currency, buy_currency, sell_amount_minor, request_id?)` | Price and lock a conversion; idempotent with `request_id` |
| `RedeemQuote(quote_id)` | Consume a quote exactly once (only by the client that created it) |
| `ListRates(base_currency)` | Indicative mid rates against a base (not lockable) |
| `grpc.health.v1.Health/Check` | Health |

With **Postman** (New → gRPC, URL `localhost:9090`, *Use server reflection*, add metadata
`x-api-key: <secret>` when keys are configured) or [grpcurl](https://github.com/fullstorydev/grpcurl):

```bash
grpcurl -plaintext -H "x-api-key: $FX_API_KEY" \
  -d '{"sell_currency":"IDR","buy_currency":"USD","sell_amount_minor":100000000,"request_id":"checkout-42"}' \
  localhost:9090 fx.v1.FxService/CreateQuote
```

```json
{
  "quoteId": "5b0d6c1e-…",
  "sellCurrency": "IDR",
  "buyCurrency": "USD",
  "sellAmountMinor": "100000000",
  "buyAmountMinor": "6123",
  "midRate": "0.00006153846153846154",
  "customerRate": "0.00006123076923076923",
  "spreadBps": 50,
  "expiresAt": "2026-11-23T04:30:30Z"
}
```

```bash
grpcurl -plaintext -H "x-api-key: $FX_API_KEY" -d '{"quote_id":"5b0d6c1e-…"}' \
  localhost:9090 fx.v1.FxService/RedeemQuote
```

## Configuration

```yaml
fx:
  spread-bps: 50          # 0.50%
  quote-ttl: 30s
  max-quote-usd: 1000000  # per-quote limit, USD equivalent of the sell side
  rates-per-usd:          # demo mid-market rates
    USD: 1
    IDR: 16250
    SGD: 1.3420
    JPY: 151.80
```

Environment: `FX_API_KEYS` (e.g. `mobile:<secret>,ledger:<secret>`; empty disables auth with a
startup warning — local development only), `GRPC_PORT` (default 9090), `GRPC_THREADS`,
`GRPC_REFLECTION_ENABLED`, `REDIS_HOST`, `REDIS_PORT`.

## Running locally

Requirements: Java 17, Maven, Docker.

```bash
docker compose up --build    # Redis + the service on :9090
GRPC_HOST_PORT=19090 docker compose up --build   # if 9090 is taken (e.g. by Prometheus)
mvn verify                   # unit tests + gRPC integration tests (Testcontainers Redis)
```

## Project layout

```
src/main/proto/fx/v1/         Protobuf contract (Java stubs are generated at build time)
src/main/java/io/github/marvinds1/fx/
  pricing/    Pricer (rate math), FxProperties
  quote/      QuoteService, Redis QuoteStore (per-client keys, GETDEL, Lua for idempotency)
  grpc/       FxGrpcService, GrpcServer lifecycle and limits, API key + access-log interceptors
```

## Roadmap

- Pull rates from a market data feed and refresh them on a schedule, with a staleness guard that refuses to quote on old rates.
- Per-customer-segment spreads and amount tiers.
- Publish `fx.quote-redeemed` events so the ledger books FX gains/losses.
- Micrometer metrics for quote/redeem rate and expiry ratio.

## License

MIT
