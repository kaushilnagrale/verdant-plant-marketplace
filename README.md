# Verdant

A multi-tenant, India-focused live-plant marketplace, built end to end
over a 12-week self-directed build: multi-seller catalog and
allocation, real payments and payouts, logistics and claims, seller
quality scoring, an AI gift concierge and plant doctor built on a real
RAG pipeline, demand forecasting, and a first real service extraction.

This is a **portfolio/learning build, not a live business** - see
[`LAUNCH_CHECKLIST.md`](LAUNCH_CHECKLIST.md) for exactly what would
still be required before this could accept a real customer's money,
and [`DECISIONS.md`](DECISIONS.md) for the real architectural
trade-offs made along the way. Every number quoted below is real and
measured against this project's own local infrastructure (see
[`METRICS.md`](METRICS.md)) - none of it is illustrative or assumed.

## What's actually here

- **Multi-tenant catalog** - sellers list against a shared canonical
  product catalog (real ONNX embeddings map a seller's own free-text
  title to the right species); Postgres row-level security enforces
  per-seller isolation at the database layer, not just in application
  code.
- **Real order lifecycle** - allocation (transit time, seller quality,
  consolidation, cost), checkout, Razorpay payment, a saga-orchestrated
  settlement/shipment flow with real compensation on failure, backed by
  a transactional outbox so a business change and its downstream events
  commit atomically.
- **Real logistics and claims** - Shiprocket booking/tracking, a
  fault-attribution engine for damaged/missing deliveries, seller
  payouts and reconciliation against a real double-entry ledger.
- **Seller quality scoring** - a damped, tiered score computed from
  real dispatch/claim/RTO events, feeding back into allocation ranking
  and a hard cap on a low-tier seller's open order count.
- **Search** - OpenSearch behind an async, outbox-fed reindex queue
  (a bounded, measured staleness window; anything that authorizes a
  state change re-reads Postgres directly, never the index).
- **AI gift concierge and plant doctor** - a real pgvector RAG pipeline
  over a real knowledge base, with the model's output validated against
  what was actually retrieved/available before it ever reaches a
  customer - proven by a 40-case golden-set evaluation harness, not
  just a handful of hand-picked examples.
- **Demand forecasting** - a seasonal-naive-plus-trend baseline over
  real order history, with a festival-calendar boost for gifting
  spikes.
- **A first real service extraction** - two least-privilege Postgres
  roles enforcing a real database-level boundary between the catalog
  and finance domains, and `catalog-service`, a genuinely separate,
  independently-runnable Spring Boot module proving the pattern end to
  end.
- **Observability** - real Micrometer business gauges, a live
  Prometheus/Grafana setup, and distributed tracing over real OTLP to
  Jaeger.

## Architecture

```
                        ┌─────────────────┐
                        │  Keycloak (OAuth2) │
                        └────────┬─────────┘
                                 │ JWT
   ┌──────────────┐      ┌──────▼───────────────────────┐      ┌──────────────┐
   │ seller-web    │◄────►│         app (monolith)        │◄────►│ Postgres (RLS)│
   │ (React/Vite)  │      │  catalog · orders · finance ·  │      │  + pgvector   │
   └──────────────┘      │  logistics · claims · quality · │      └──────────────┘
                          │  notifications · assistant ·    │
                          │  forecasting · search projection│      ┌──────────────┐
                          └───┬──────────┬──────────┬───────┘      │    Redis     │
                              │          │          │              └──────────────┘
                       outbox │   Kafka  │   async   │
                              ▼  (Redpanda)▼   reindex▼
                      ┌───────────┐  ┌──────────┐  ┌─────────────┐
                      │  ledger/  │  │  events  │  │ OpenSearch  │
                      │  payouts  │  │(projectors)│ │ (search)    │
                      └───────────┘  └──────────┘  └─────────────┘

   ┌───────────────────┐   restricted role   ┌──────────────┐
   │  catalog-service    │◄───────────────────►│  Postgres    │
   │ (separate process)  │                     │ (shared db)  │
   └───────────────────┘                     └──────────────┘

   Prometheus ◄─ /actuator/prometheus        Jaeger ◄─ OTLP/HTTP
        │                                          ▲
        ▼                                          │
     Grafana                              app's real spans
```

**Stack**: Java 21 / Spring Boot 4, Postgres 16 (pgvector, row-level
security), Redis, Kafka-protocol (Redpanda), OpenSearch, Keycloak,
Razorpay, Shiprocket, a local ONNX embedding model (catalog mapping and
RAG retrieval - no external LLM API), React 19 + TypeScript (Vite)
frontends, Prometheus/Grafana/Jaeger.

## Running it locally

```bash
# 1. Infrastructure (Postgres, Redis, Kafka, OpenSearch, Keycloak,
#    LocalStack S3, and - optionally - Prometheus/Grafana/Jaeger):
cd infra/docker && docker compose up -d

# 2. One-time dev seed data + a real Keycloak realm/client/users
#    (idempotent - safe to re-run any time the Keycloak container
#    loses its state, e.g. after a restart):
cd ../.. 
./scripts/setup-keycloak.sh
docker exec -i verdant-postgres psql -U verdant -d verdant < scripts/seed-pincodes.sql

# 3. The app (Flyway migrates the schema automatically on boot):
SPRING_PROFILES_ACTIVE=local ./gradlew :app:bootRun

# 4. A frontend, in a separate terminal:
cd frontend/seller-web && npm install && npm run dev   # or frontend/buyer-web
```

`scripts/token.sh <username>` mints a real bearer token against the
local Keycloak (`ops-1` for platform-admin endpoints, `seller-a` for a
seller identity) for exercising authenticated endpoints directly.

To also stand up `catalog-service` (the separate extracted module):

```bash
DB_USER=catalog_service_role DB_PASSWORD=catalog-service-dev-password \
  ./gradlew :catalog-service:bootRun
```

## Testing

```bash
./gradlew test          # full suite (Testcontainers - needs Docker running)
./gradlew :app:test --tests "com.verdant.assistant.*"   # a single area
```

The suite is real infrastructure throughout (real Postgres, Redis,
Kafka, and - for search-specific tests - real OpenSearch via
Testcontainers), not mocked at the boundaries that matter. A small
number of tests are documented, understood, order-dependent flakes
under full-suite-scale contention on shared background workers (see
`OutboxTest`'s and `ProjectionTest`'s own doc comments) - re-running
them in isolation is expected to pass cleanly.

## Project layout

```
app/                    the monolith - one Spring Boot app, packages per domain
  src/main/java/com/verdant/
    catalog/ inventory/ search/          product catalog, listings, OpenSearch projection
    orders/ checkout/ cart/ payment/     order lifecycle, allocation, Razorpay
    finance/                            ledger, payouts, reconciliation
    logistics/ claims/                  Shiprocket, fault attribution
    identity/ seller/ quality/          onboarding, seller accounts, quality scoring
    notifications/                      WhatsApp delivery
    assistant/                          gift concierge, plant doctor (RAG)
    forecasting/                        demand forecasting
    observability/                      business metrics
  src/main/resources/db/migration/      Flyway migrations (V1..V39+)
catalog-service/         a real, separate extracted service (see DECISIONS.md)
common/common-domain/    shared value types
common/common-testing/   shared Testcontainers base classes and fixtures
content/knowledge/       the assistant's real markdown knowledge base
frontend/seller-web/     seller dispatch/inventory/payouts app (React)
frontend/buyer-web/      buyer catalog/cart/checkout app (React)
infra/docker/            docker-compose for all local infrastructure
infra/observability/     Prometheus/Grafana provisioning and dashboards
scripts/                 load tests (k6), dev seed data, ops scripts
```

## Where to look next

- [`LAUNCH_CHECKLIST.md`](LAUNCH_CHECKLIST.md) - what's actually
  verified vs. what's an honest, named gap.
- [`DECISIONS.md`](DECISIONS.md) - the real architectural trade-offs,
  including the ones made under real time/risk constraints.
- [`METRICS.md`](METRICS.md) - every real, measured number this
  project has produced about itself.
