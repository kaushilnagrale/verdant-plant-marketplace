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
