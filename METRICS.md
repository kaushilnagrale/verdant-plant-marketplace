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
