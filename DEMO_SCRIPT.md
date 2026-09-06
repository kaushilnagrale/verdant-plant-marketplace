# Demo script

A walkthrough script for a real screen recording of Verdant - **not a
recording itself**, since this autonomous build session has no way to
capture video. Every step below is a real command or a real screen
against the real local stack; anyone with the repo and Docker can
follow this exactly and see the same thing. Timings are a rough guide
for a ~6-minute walkthrough, not a hard requirement.

## Before recording

```bash
cd infra/docker && docker compose up -d
cd .. && ../scripts/setup-keycloak.sh
docker exec -i verdant-postgres psql -U verdant -d verdant < scripts/seed-pincodes.sql
SPRING_PROFILES_ACTIVE=local ./gradlew :app:bootRun &

# Seed a few real listings so the catalog isn't empty on camera, and
# ingest the assistant's knowledge base + backfill search:
TOKEN=$(./scripts/token.sh ops-1)
NEW_INDEX="listings-v$(date +%Y%m%d%H%M)"
curl -s -X PUT "localhost:9200/$NEW_INDEX" -H 'Content-Type: application/json' -d @infra/opensearch/listings-index.json
curl -s -X POST localhost:9200/_aliases -H 'Content-Type: application/json' \
  -d "{\"actions\":[{\"add\":{\"index\":\"$NEW_INDEX\",\"alias\":\"listings\"}}]}"
curl -s -X POST http://localhost:8080/v1/admin/knowledge/ingest -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost:8080/v1/admin/search/backfill -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"targetIndex\":\"$NEW_INDEX\"}"

cd frontend/buyer-web && npm run dev &
cd ../seller-web && npm run dev -- --port 5174 &
```

## 0:00 - 0:30 — Cold open

> "This is Verdant - a multi-tenant live-plant marketplace for India,
> built solo over 12 weeks. Everything you're about to see is running
> against real infrastructure on my own machine right now - real
> Postgres, real Kafka, a real payment sandbox, a real local AI model.
> Nothing here is a mockup."

Show `docker ps` briefly - a real stack of ten-odd containers.

## 0:30 - 1:30 — Buyer flow

Open `http://localhost:5173` (buyer-web).

- Browse the catalog grid, open a product, add to cart.
- Show the cart page's shipment-split preview (multiple sellers, split
  into separate shipments with their own transit estimates).
- Checkout with a Razorpay **test** card - narrate that the payment
  gateway integration is real, the money is not (`rzp_test_` keys,
  never `rzp_live_`, `KeyModeGuard` enforces this can never invert in
  prod).
- Land on the confirmation page; mention the real saga underneath
  (payment → outbox → settlement → shipment booking) with real
  compensation if any step fails.

## 1:30 - 2:30 — Seller flow

Switch to `http://localhost:5174` (seller-web). Paste a seller token
(`./scripts/token.sh seller-a`) on the login screen.

- Today's orders → mark one packed, then dispatched.
- Payouts & quality screen - real quality tier, real damped score.
- Stock adjust on a listing; mention the real inventory `CHECK`
  constraint that turns a bad adjustment into a 409, not a corrupted
  row.

## 2:30 - 4:00 — The AI features (the most interesting part)

Narrate: "No hosted LLM here - a local embedding model plus a real
retrieval-augmented pipeline, with the actual engineering effort spent
on the guardrails, not the model call."

Gift concierge (via `curl` or a quick script - no dedicated UI was
built for this yet, an honest, named gap):

```bash
curl -s -X POST http://localhost:8080/v1/assistant/gift-concierge \
  -H "Content-Type: application/json" \
  -d '{"query":"diwali gift","destinationPincode":"411001"}' | python3 -m json.tool
```

Point out: the response cites real retrieved knowledge-base chunks, and
recommends only listings that were actually checked for real stock and
delivery to that pincode - "the model never gets to invent a listing
or a citation; a Java validation step throws if it tries, and a
40-case golden set proves that holds."

Plant doctor - same shape, a symptom description standing in for a
real photo upload:

```bash
curl -s -X POST http://localhost:8080/v1/assistant/plant-doctor \
  -H "Content-Type: application/json" \
  -d '{"userId":"demo","imageKey":"yellow-leaves.jpg"}' | python3 -m json.tool
```

## 4:00 - 5:00 — Observability

Open Grafana (`http://localhost:3000`) - the "Verdant - Business &
Platform Health" dashboard, auto-provisioned, showing real live
metrics from the app that's been running this whole demo.

Open Jaeger (`http://localhost:16686`) - pick the `verdant` service,
show a real trace from one of the background jobs.

> "Both of these came up with zero manual dashboard-building - the
> config is checked into the repo and provisions itself."

## 5:00 - 5:45 — The numbers, honestly

Show `METRICS.md` on screen briefly, or read out loud:

> "A real festival-spike load test - 20 concurrent users, not the
> 500-user production target this laptop can't meaningfully validate -
> handled about 450 requests a second with a 95th-percentile response
> time under 120 milliseconds, even for the AI endpoint doing real
> embedding inference on every request. Zero failures across 54,000
> requests."

## 5:45 - 6:00 — Close

> "This is a portfolio build, not a live business - the launch
> checklist in the repo is explicit about exactly what's real
> engineering versus what would still need a real company behind it
> before this could take a real customer's money. But everything you
> just saw - the payment flow, the saga, the AI guardrails, the
> observability stack - is real, running code, not a slide."

## Notes for whoever actually records this

- Silence the terminal bell / hide notifications before recording.
- The gift-concierge/plant-doctor calls are `curl` because no
  dedicated buyer-facing UI screen was built for the assistant this
  session - say so on camera rather than editing around it; it's a
  real, small, named gap (`LAUNCH_CHECKLIST.md`), not a defect to hide.
- If a screen looks empty, it's almost always missing seed data
  (`scripts/seed-pincodes.sql`, a re-run of the knowledge-base ingest,
  or the search backfill above) - the "Before recording" block exists
  precisely so this doesn't happen live.
