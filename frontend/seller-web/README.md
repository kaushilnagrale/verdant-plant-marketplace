# Verdant Seller

A real, functional (not exhaustively polished) mobile-first web app for
sellers to run their day: today's orders, adding a plant, adjusting
stock, and their own quality score - against the real backend APIs
built through Week 10 of this project.

## Running it

```bash
npm install
npm run dev          # against VITE_API_BASE_URL, defaulting to http://localhost:8080
npm run build         # typecheck + production bundle
npm test              # the offline-queue unit tests
```

Sign in with a real Keycloak-issued token (`../../scripts/token.sh seller-a`
from the repo root, against the real local dev stack) - there is no real
OIDC login flow in this scope, see `src/lib/auth.ts`'s own comment.

## What's real here

- All five screens (Today, order detail, add plant, stock adjust,
  payouts/quality) call the real backend endpoints built for them,
  including three that didn't exist before this work (stock-adjust,
  quality view, vacation mode - see the backend's own commit history).
- The offline action queue (`src/lib/queue.ts`) is real: actions persist
  to IndexedDB (Dexie) before any network call, retry on reconnect, and
  a permanent (4xx) failure is dropped rather than retried forever. Real
  concurrency bug found and fixed while testing it directly: two
  overlapping `flush()` calls (the fire-and-forget one `enqueueAction()`
  triggers, racing an explicit one) could send the same action twice -
  fixed with an in-flight guard, same "claim before acting" shape as
  this project's own backend queues.
- i18n (Hindi/English) is wired and switchable, but the Hindi labels are
  standard-dictionary translations, not reviewed by a native speaker -
  56.3's own explicit guidance ("ask your mother which language she
  actually prefers... guessing produces an interface that reads as
  translated rather than native") was not something this session could
  do. Real deployment needs that real review before shipping.

## What's honestly out of scope

- **Camera photo capture for "add plant"** - the backend's own image
  upload (S3, Week 3) already exists; wiring a camera-capture UI to it
  is real, separate frontend work this pass didn't reach.
- **A real OIDC login screen** - a pasted token stands in for it.
- **Playwright end-to-end coverage** - `queue.ts`'s own real logic has
  real unit tests; a full login→receive→pack→dispatch browser-driven
  test is a real, separate investment this scope didn't include.
- **The actual gate this day is built around**: sitting with a real
  person, giving no instructions, and timing three real tasks (56.5).
  That is a genuine, in-person usability session this autonomous build
  session cannot perform, no matter how much of the app above is real -
  fabricating timing numbers for a session that never happened would be
  worse than saying plainly that it didn't. If you're reading this and
  you're the one who can run that session: do it before trusting this
  screen set is actually right. Every hesitation is a bug (56.5's own
  words), and this scope had no one to watch hesitate.
