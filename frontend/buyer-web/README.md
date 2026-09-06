# Verdant buyer-web

The Week 6 (Day 34) frontend - one Vite + React + TypeScript app covering
both the buyer flow and the seller dispatch flow (the runbook describes
these as separate apps; combined here with client-side routing to keep
one app to run instead of two, functionally equivalent).

## Screens

- `/` - catalog grid (buyer)
- `/product/:id` - product detail + add to cart (buyer)
- `/cart` - cart with a quantity stepper per line, a real delivery
  address form, shipment-split preview, checkout with real Razorpay
  (buyer) - requires being signed in
- `/register`, `/login` - a real buyer account (see below)
- `/orders`, `/orders/:orderId` - order history and per-order tracking
  (real shipment status/courier/AWB and the real courier event
  timeline, once a seller has dispatched it)
- `/confirmation/:orderId` - order confirmation (buyer)
- `/seller/login` - paste a bearer token (see below)
- `/seller/orders` - today's orders, mark packed then dispatched (seller)

## Running it

```bash
npm install
npm run dev
```

Requires the backend running locally (`SPRING_PROFILES_ACTIVE=local ./gradlew :app:bootRun`
from the repo root) with the usual Docker infra up (`docker compose up -d`
in `infra/docker`). The API base URL defaults to `http://localhost:8080`;
override with `VITE_API_BASE_URL` if needed.

## Buyer auth

A real account, unlike the seller side below: `/register` calls the
backend (`POST /v1/buyers/register`), which creates a real Keycloak user
(via a narrowly-scoped service-account admin client, never the
platform's own bootstrap admin) and a matching local `buyers` row.
`/login` gets a real token directly from Keycloak's own token endpoint
(a public client, `verdant-buyer-web`, direct-grant login) - the
backend is never in that request path at all, the same real mechanism
`scripts/token.sh` already uses for sellers, just called from the
browser. The token is stored in `localStorage` and attached to every
`/v1/buyers/**` and `/v1/checkout/**` request automatically
(`api/client.ts`).

Run `./scripts/setup-keycloak.sh` from the repo root first (idempotent)
- it provisions the `verdant-buyer-web`/`verdant-backend` Keycloak
clients and the `BUYER` realm role this all depends on, the same way
it already provisions the seller-side setup below.

## Seller auth

There is no login screen built here on purpose - seller auth goes through
Keycloak directly, the same way every backend day this project was
verified: `./scripts/token.sh <seller-username>` from the repo root, then
paste the resulting token into `/seller/login`. It's stored in
`localStorage` and attached to every `/v1/seller/**` request automatically.

## CORS

The backend explicitly allows `http://localhost:5173` (Vite's default dev
port) via `verdant.cors.allowed-origins` - a real, previously-missing gap
found and fixed while building this (permitAll on an endpoint governs
authorization, not whether a browser on a different origin may even send
the request in the first place). Buyer login talks to Keycloak directly,
not the backend, so it needs the *same* origin allowed on the Keycloak
side too - `verdant-buyer-web`'s own `webOrigins` setting
(`scripts/setup-keycloak.sh`), a separate real config surface from the
app's CORS bean.
