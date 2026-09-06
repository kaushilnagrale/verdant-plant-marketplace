# End-to-End Rehearsal

This is a rehearsal, not a launch report. No real customer, seller, or
payment is involved anywhere in this document — see `LAUNCH_CHECKLIST.md`
for what would actually be required before that's true. What follows is a
real run of the real pipeline against real infrastructure, with test data,
timestamped and verified by direct database inspection rather than assumed.

## What was exercised, in one pass

Catalog → checkout → real Razorpay order creation → payment verification →
order saga (inventory already reserved at checkout, so the saga starts at
`INVENTORY_RESERVED`) → settlement posted to the ledger → shipment booked →
seller views the order over a real Keycloak-issued JWT → seller packs →
seller dispatches.

## Run record — 2026-09-03

| Step | What happened | Evidence |
|---|---|---|
| Seller/listing setup | `Blue Leaf Nursery` (an existing approved seller, previously without a listing) given an origin pincode and one listing — "Rehearsal Snake Plant - 6 inch pot", ₹349.00, 15 in stock, mapped to the `Snake Plant` canonical product | direct SQL, explicitly commented as rehearsal data |
| Checkout | `POST /v1/checkout`, 2 units | Order `90559c37-184e-42b6-88fc-a156c2f327df` created 20:12:09 UTC, total ₹698.00 |
| Real Razorpay order | Created via the actual Razorpay Test Mode API (not a stub) | `razorpayOrderId = order_TXgvszisV4FhEG`, real key `rzp_test_us_...` |
| Payment verification | `POST /v1/checkout/{orderId}/verify` — signature computed with the real Key Secret exactly as `checkout.js` would produce it (see the note below on why this step is simulated, not a completed real payment) | HTTP 200, order flipped to `PAID` at 20:12:24 UTC |
| Saga | Ran unattended on its normal 1s scheduler tick — no manual intervention | `order_sagas.state = COMPLETED`, `furthest_state = COMPLETED`, `attempts = 0`, `last_error = NULL` |
| Settlement | Posted to the double-entry ledger | `ledger_transactions` row, `kind = ORDER_SETTLEMENT`, `reference = 90559c37-...`, posted 20:12:26 UTC |
| Shipment booked | Per-seller shipment row created by the saga | `shipments.status = BOOKED` |
| Seller views order | `GET /v1/seller/orders` with a real Keycloak-issued bearer token (`seller-b`, mapped to Blue Leaf Nursery via `seller_users`) | order appears with correct item, quantity, and shipment status |
| Seller packs | `POST /v1/seller/orders/{orderId}/pack` | HTTP 200 |
| Seller dispatches | `POST /v1/seller/orders/{orderId}/dispatch` | HTTP 200, `shipments.status = DISPATCHED` |

Total elapsed time from checkout to a fully dispatched shipment: under 30
seconds, almost entirely spent waiting on the saga's 1-second poll interval
— there is no manual step in the middle once payment is verified.

## Why "payment verification" isn't a completed real payment

Actually completing a Razorpay Test Mode payment requires their hosted
Checkout widget (a browser flow) and, per the Day 23 finding, this
project's Razorpay account has KYC pending on Razorpay's side — a genuine
external blocker unrelated to this project's code. Order **creation**
against the real Razorpay API is proven real above. The verification step
computes the same HMAC-SHA256 signature `checkout.js` would hand the server
after a real payment, using the real Key Secret — this exercises the real
verification code path with real cryptographic material, but the payment
capture itself is simulated rather than clicked through a browser. This is
the same approach used and documented back in Week 4 (Day 23) and is not a
new gap introduced here.

## What this run adds on top of the Day 35 disaster drill

Day 35 proved the pipeline survives a real infrastructure failure
(Postgres restarting mid-saga). This run proves the pipeline completes
cleanly end to end under normal conditions, including the part Day 35
didn't touch: the seller-facing pack/dispatch flow, driven through the real
API with a real Keycloak-issued token rather than a direct database check.

## Known, already-documented gaps, not re-litigated here

See `LAUNCH_CHECKLIST.md` for the full list of what's genuinely missing
before this could serve a real customer (live payment keys, a real catalog,
legal documents, support contact, backup/restore testing, monitoring). None
of that is faked or implied to be done by this document.

## Payouts and reconciliation

The runbook's Day 47 asks for two things this project genuinely cannot
produce honestly: a real four-consecutive-day manual observation window
(there is no way to fast-forward real calendar time), and "the first real
payout" reaching an actual bank account (there is no real bank account or
business behind this project — `PayoutGateway` is stub-only by explicit
decision, Day 44). Faking either would misrepresent what actually happened,
the same reasoning applied to Days 35-36's soft launch and Week 7's shadow
observation. What follows is what's actually true instead.

**What's real:**
- `auto_payout` defaults `false` (`V32__auto_payout_flag.sql`) - every
  cycle in this project is approved by a human through
  `PayoutAdminController`, gated to `PLATFORM_ADMIN` only, never a seller.
- `PayoutCycleBuildJob` runs weekly (Monday 05:00 IST), builds one cycle
  per approved seller with eligible deliveries for the week that just
  closed, and correctly isolates one seller's failure from every other
  seller's cycle in the same run - a real bug, caught and fixed this same
  day (a struggling seller's `InsufficientPayableException` used to abort
  the entire batch; verified via `oneSellerFailingAutoInitiate_
  doesNotBlockAnotherSellersPayout`).
- The morning checklist queries in 47.1 (`reconciliation_runs`,
  `reconciliation_breaks`, the global ledger invariant, cycles awaiting
  approval) all run against a real, populated schema - not aspirational
  SQL against tables that don't exist.
- The full approve → initiate → ledger-post → provider-transfer chain is
  proven end to end against the stub gateway: `PayoutAdminControllerTest`
  drives it through real HTTP with a real Keycloak-shaped JWT, and
  `PayoutTest`'s 8-way concurrency test and reversed-on-failure test cover
  what would matter most if the gateway were ever swapped for a real one.

**What isn't real, and why:**
- No four-day observation happened - this session ran in one sitting.
  The mechanism it would rely on (the reconciliation job, the pending-
  approval query) is real and tested; the passage of real time watching
  it isn't something a single session can produce.
- No payout reached a real bank account - `StubPayoutGateway` returns a
  fabricated provider reference, never calls anything external.
- `auto_payout` has never been flipped to `true` outside a test - flipping
  it for real would need the four-day clean streak above to actually have
  happened first, which it hasn't.

**What would need to be true before a real payout could happen**: a real
Razorpay (or equivalent) payout/vendor-transfer integration in place of
`StubPayoutGateway`, a real seller bank account with penny-drop
verification (flagged back at the Week 7→8 boundary as the user's own
action item, not something this session can do), and a genuine multi-day
observation period against real order traffic - none of which currently
exist, consistent with every other honestly-documented gap in
`LAUNCH_CHECKLIST.md`.

## Search: the consistency budget, and disposability

The stated rule: **browsing reads may be stale, but reads that authorize
a state change may not.** OpenSearch is a read model fed asynchronously
from the transactional outbox (create → outbox → Kafka → `ListingProjector`
→ `search_reindex_queue` → `ReindexWorker` → OpenSearch); `CartPricingService`
- used by both the standalone cart quote and checkout itself - never reads
that index at all, re-checking active/stock/seller status against Postgres
on every call instead.

**Real, measured numbers** (`scripts/measure-lag.sh`, run against this
project's own local docker-compose stack - real Postgres, real Redpanda,
real OpenSearch, real ONNX auto-mapping, 20 real listings created one at a
time through the real `POST /v1/listings` endpoint):

```
p50: 991ms   p95: 1058ms   max: 1083ms
```

**This number is real, not the runbook's own illustrative ~800ms** - it's
measured on this specific machine, and it measures the FULL real path for
a brand new listing, including the real async auto-mapping step
(`ListingMappingWorker`'s ONNX embedding computation), not just the
reindex hop by itself. A listing that's already mapped and is merely
updated (a price change, a deactivation) skips that step and would show a
smaller number - this measurement deliberately used the slower, complete
"new listing" path so the stated budget doesn't understate real worst-case
staleness. **Search is eventually consistent within roughly 1.1 seconds
at the observed max, and every state-changing read (cart quote, checkout)
revalidates against Postgres regardless of how stale the index is.**

**Search latency under load** (`scripts/search-load.js`, real k6, real
running app): a genuine 500-virtual-user, 4-minute run is more than this
single-machine local sandbox can meaningfully validate as a production
capacity number, so this ran a real, smaller version instead - 20 VUs
sustained for 30s, 36,620 real requests, 0 failures:

```
p95: 24.87ms   p99: 31.2ms   (both comfortably under the 150ms/200ms targets)
```

Directionally excellent at this scale, but explicitly **not** a
verification that the real 500-VU/200ms-p99 target holds - that needs a
real multi-core server and a real, larger seeded catalog to mean
anything, neither of which this environment has. Re-run `k6 run
scripts/search-load.js` (the full version, `BASE_URL` pointed at a real
deployment) before trusting this number at production scale.

**Blue-green reindexing, verified live**: ran `scripts/reindex-all.sh`
against this project's own real (messy, multi-day-old) local dev catalog
- 17 real listings, of which 4 are actually mapped to a canonical product
(`ProjectionQueryRepository`'s own deliberate `INNER JOIN` correctly
excludes the other 13 UNMAPPED/PENDING_REVIEW ones) - backfilled all 4
into a fresh `listings-v202609050038` index, confirmed the count matched,
flipped the `listings` alias to it, and confirmed real search
(`GET /v1/search?q=snake+plant&pincode=411001`) kept working uninterrupted
through the flip - zero downtime, exactly the point of doing this via an
alias rather than rebuilding an index in place.

**Disposability**: `ProjectionTest.openSearchDown_failsGracefully_
doesNotThrow` proves a genuinely unreachable OpenSearch cluster fails the
*reindex* cleanly (the item goes back on the queue, nothing throws) rather
than cascading; separately, no code anywhere on the real order-placement
path (`CartPricingService`, `CheckoutService`, `InventoryService`,
`ReservationService`) takes an `OpenSearchClient` dependency at all, which
is a stronger claim than a try/catch - the write path cannot depend on
search being up because it has no way to reach it in the first place. If
OpenSearch is down: search degrades (empty results, or the real 500 a
genuinely unreachable cluster produces via `SearchController`, left
un-caught deliberately - see that class's own comment on why a fabricated
empty result would be a worse, silent lie) but checkout, payment, and
fulfillment keep working, because none of them were ever wired to it.

**Two real, environment-specific bugs found and fixed while producing
these numbers, both worth remembering**: (1) the runbook's own
`measure-lag.sh` creates listings titled "Lag Test N" - a title the real
auto-mapping pipeline correctly never confidently maps to a canonical
product, and an unmapped listing is deliberately excluded from the index
entirely (Week 3's own design), so the script hung forever waiting for a
document that was never going to exist; fixed by using a real,
confidently-mappable title ("Money Plant") instead, which also makes the
measurement more honest (it now includes the real auto-mapping latency).
(2) `date +%s%3N` is GNU-only - this project's own dev machine is macOS,
where BSD `date` has no `%N` and silently emits the literal text "3N"
instead of erroring, corrupting the arithmetic; fixed with a small
`python3`-based millisecond clock, which is genuinely portable rather
than GNU-specific.

## Observability and festival-spike load (Week 12)

**Real, live-verified against this project's own docker-compose stack**,
not just config files that look plausible:

- **Prometheus** (`infra/observability/prometheus.yml`) scrapes
  `/actuator/prometheus` on a real interval and reports the target
  `up`, verified via Prometheus's own `/api/v1/query` - e.g.
  `verdant_orders_paid_today` and `search_index_lag_seconds_count`
  both returned real (zero, on a freshly-seeded dev database) values
  through a live scrape, not a mocked response.
- **Grafana** auto-provisions its Prometheus datasource and the
  `Verdant - Business & Platform Health` dashboard on boot, verified
  via Grafana's own `/api/search` and `/api/datasources` - both
  appear with no manual setup step.
- **Jaeger**: real spans for every `@Scheduled` job
  (`outboxDrainer.drain`, `reindexWorker.drain`,
  `notificationSender.sendQueued`, `businessMetrics.refresh`, ...)
  reach Jaeger for real over OTLP/HTTP, confirmed via Jaeger's own
  `/api/services` and `/api/traces`. **Real, honest gap**: HTTP
  request-level spans (e.g. `GET /v1/catalog` as its own trace) do not
  currently appear - `http.server.requests` IS recorded as a metric
  (proving the underlying Observation fires), but something in Boot
  4.1.1's Observation-to-Tracing bridge for web requests specifically
  isn't connected the same way it is for scheduled tasks. Not chased
  further past a few real, targeted checks - the harder, load-bearing
  part (a real OTLP pipeline reaching a real trace backend) is proven;
  this is a narrower, documented incompleteness, not a hidden one.
- **A real, two-part configuration bug, found only by an actual boot +
  live check, not by reading the dependency docs**: (1) Spring Boot
  4.1.1 splits what used to be `spring-boot-starter-actuator`'s
  monolithic autoconfiguration into many fine-grained modules -
  `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`
  alone give the raw bridge/exporter classes but nothing to actually
  construct and register them; the missing piece is the separate
  `spring-boot-micrometer-tracing-opentelemetry` module, found by
  listing this project's own actual resolved classpath and confirming
  `spring-boot-actuator-autoconfigure-4.1.1.jar` contains zero
  tracing/otlp/zipkin classes at all. (2) Even with that module
  present, the real property prefix in this exact Boot version is
  `management.opentelemetry.tracing.export.otlp`, not
  `management.otlp.tracing` (a 3.x-era assumption) - found by
  decompiling `OtlpTracingProperties`'s own `@ConfigurationProperties`
  annotation directly rather than guessing a second time, the same
  method this project has used for every other library-version
  surface mismatch (the OpenSearch client, Jackson 3, MockMvc's
  autoconfigure package).
- **A real security gap, found by Prometheus's own scrape actually
  returning 401**: `/actuator/prometheus` fell under
  `SecurityConfig`'s default `.anyRequest().authenticated()` - a
  scrape config has no way to hold a refreshable JWT, so every real
  scrape attempt failed. Fixed with the same `permitAll` treatment
  `/actuator/health` already has; noted in `SecurityConfig`'s own
  comment that a real deployment should more properly put this on a
  separate, network-restricted management port instead.
- **Keycloak realm setup was never scripted** - a real, previously
  invisible gap, discovered when this exact dev Keycloak container
  (no persistent volume, `start-dev`) was lost to an unrelated Docker
  Desktop restart mid-session and every manual-smoke-test credential
  with it. Fixed for real with `scripts/setup-keycloak.sh` (idempotent,
  provisions the `verdant` realm, the `verdant-seller` client
  `scripts/token.sh` expects, and `ops-1`/`seller-a` users) - itself
  needed one more real fix along the way: Keycloak 26's `VERIFY_PROFILE`
  required action silently blocks a direct-grant login for a user
  created with only a username, surfaced only as a generic
  `invalid_grant: Account is not fully set up` from the token endpoint.

**Festival-spike load** (`scripts/festival-spike-load.js`, real k6
against the real running app, real seeded catalog, real ingested
knowledge base): same honest scale-down as the search-load run above,
20 VUs rather than a production target, weighted 70% search / 20% gift
concierge / 10% catalog browse to resemble a real Diwali-week traffic
mix. 53,874 real requests over 2 minutes, 0 failures:

```
catalog:    p95 = 34.3ms
search:     p95 = 52.87ms
concierge:  p95 = 118.92ms   (real embedding inference + pgvector retrieval - a
                              genuinely heavier real cost than an indexed search,
                              given its own looser 1500ms threshold)
overall:    p95 = 83.22ms   throughput ≈ 449 req/s
```

Same caveat as the search-only run: real numbers at laptop scale, not a
verification of real production capacity.
