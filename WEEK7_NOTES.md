# Week 7 — Logistics, Weather Gating, Shadow Observation

Like `METRICS.md`, this is a rehearsal record, not a production report —
no real customer, seller, or courier pickup is involved anywhere in this
document.

## What "shadow observation" means for this project

The runbook's Day 42 asks for `weather_gating` to run in shadow mode for
three real days against real order traffic, then for the thresholds to be
tuned from what was actually observed. That's not something this project
can produce honestly: there is no real order traffic, and three real days
haven't passed. What follows is what's actually true instead:

- `weather_gating` **is** seeded in exactly the state the runbook asks
  for — `enabled = false, shadow_mode = true` (`V23`, Day 37) — so the
  mechanism is live-observing by default the moment this is ever deployed
  for real, without anyone needing to remember to turn it on.
- The gate's decision logic (heat threshold per plant category, lowest
  tolerance in a mixed parcel, flood/rainfall, fail-open on missing or
  stale data, shadow-vs-real recording) is exercised by 10 real scenarios
  (`DispatchGateTest`) against real Postgres, not simulated.
- The full lifecycle around a shadow decision — a hold gets recorded,
  reviewed twice daily, escalated or auto-released, or overridden by the
  buyer — is exercised by 5 more real scenarios (`HoldLifecycleTest`).
- The queries the runbook describes for reviewing shadow decisions are
  real, working SQL against this project's actual schema (below) — ready
  to run against real data the moment there is any.

## The real review queries (verified to run correctly against this schema)

```sql
SELECT reason, count(*), min(held_at)::date AS since
  FROM dispatch_holds WHERE shadow = TRUE GROUP BY reason;

SELECT s.id, s.destination_pincode, h.reason, s.delivered_at IS NOT NULL AS delivered
  FROM dispatch_holds h JOIN shipments s ON s.id = h.shipment_id
 WHERE h.shadow = TRUE ORDER BY h.held_at DESC LIMIT 20;
```

What you'd actually be looking for, once there's real data: did the
shipments it would have held arrive in poor condition anyway (threshold
too loose), or did it flag routine shipments during an unremarkable week
(threshold too aggressive)? Neither question has a real answer yet.

## Real end-to-end check performed this week

A real order was driven through the full pipeline with weather gating and
the new dispatch-orchestration wiring active: checkout → real Razorpay
order → payment verification → the live saga (unattended, its own
scheduler) → a real `BOOKED` shipment with no AWB. Confirmed via psql:
shipment `status = BOOKED`, no active hold (no bad-weather forecast was
seeded for this pincode, and the gate correctly failed open on the
missing data rather than blocking), ledger still balances to exactly
`0.00` globally. `shiprocket_booking` stayed off (its real default), so
no real courier call was attempted — matches the intended safe-by-default
posture for a feature that hasn't been observed yet.

## Real external integrations, and their actual status

- **OpenWeather**: live-verified. Real forecast data fetched and parsed
  successfully via the free `/data/2.5/forecast` endpoint (see commit
  history for the One Call 3.0 subscription gotcha this replaced).
- **Shiprocket**: built correctly against their documented API, credentials
  stored, but **not** live-verified — requests from this sandbox are being
  rejected in a way that looks like an anti-bot block, not a credentials
  problem (inconsistent error messages across identical requests). Revisit
  from a real machine/network when there's a reason to.

## What's still honestly missing before this could gate a real order

See `LAUNCH_CHECKLIST.md` for the general list. Specific to Week 7: real
per-listing package dimensions (weight/size is approximated from
`canonical_products.size_tier` — see `SizeTierDimensions`), a real inbound
channel for a buyer to actually trigger `HoldOverrideService` (the service
exists and is tested; nothing calls it from outside a test yet), and the
three real days of observation this document can't fabricate.
