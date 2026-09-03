# Verdant buyer-web

The Week 6 (Day 34) frontend - one Vite + React + TypeScript app covering
both the buyer flow and the seller dispatch flow (the runbook describes
these as separate apps; combined here with client-side routing to keep
one app to run instead of two, functionally equivalent).

## Screens

- `/` - catalog grid (buyer)
- `/product/:id` - product detail + add to cart (buyer)
- `/cart` - cart, shipment-split preview, checkout with real Razorpay (buyer)
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
the request in the first place).
