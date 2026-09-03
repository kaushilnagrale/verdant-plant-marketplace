# Launch Checklist

Verdant is a portfolio/learning build, not a live business. This checklist
records what the *engineering* side has actually verified, and — separately —
what would still be required before this could accept a real customer's
money. The second list is intentionally left undone: filling it in would mean
fabricating a company, a legal identity, and a supply chain that don't exist.

## Engineering — verified for real this session

- [x] Secret scan of tracked source (`grep -rn "rzp_test\|rzp_live\|password\|secret"`
      across `*.yml`/`*.java`) — no literal secrets in git-tracked files;
      the one file that does hold a real-looking value
      (`application-local-secrets.yml`) is confirmed untracked, gitignored,
      and absent from git history.
- [x] `KeyModeGuard` — a `prod`-profile Spring context refuses to start if
      `RAZORPAY_KEY_ID` looks like a test key (`rzp_test_...`). Verified
      against two real Spring Boot process starts: a test key aborts startup
      with `IllegalStateException`; a live-shaped key starts cleanly.
- [x] Disaster drill — a real checkout, payment, saga run, settlement, and
      shipment booking survived a genuine mid-flight `docker restart` of
      Postgres with zero application-visible errors (HikariCP absorbed the
      dropped connections transparently). Verified via log inspection and a
      direct psql check of final state (order `PAID`, saga `COMPLETED`,
      ledger transaction posted, shipment `BOOKED`).
- [x] CORS — an explicit `CorsConfigurationSource` bean exists and was
      verified against a real browser-origin (Vite dev server) request, not
      just `curl`.
- [x] Full order lifecycle (allocation → checkout → payment → outbox →
      saga → settlement → shipment → seller dispatch) exercised end to end
      with real infrastructure, including compensation paths, across
      Weeks 5–6.

## Business/operational — NOT done, and why they're out of scope here

These items only make sense with a real company behind them. Doing any of
them for a project with no actual nursery, no actual customers, and no
actual bank account would mean fabricating a business identity — so they're
listed as honest gaps, not completed.

- [ ] **Live Razorpay keys** — requires a registered business entity and a
      completed KYC process with Razorpay. Not applicable without one.
- [ ] **Real product photos and prices** — the 5 seeded canonical products
      are placeholder data (see the "Seed 5 canonical products" commit),
      explicitly not a real catalog.
- [ ] **Privacy policy / Terms of Service** — these are legal documents that
      make representations about a real company's data handling and
      liability. Generating boilerplate text here would misrepresent a
      project as having gone through that process; it hasn't.
- [ ] **Support contact (phone/email/hours)** — no real support operation
      exists to staff one.
- [ ] **Seller vacation mode / inventory pause** — a real operational
      feature for real sellers managing real stock; not built, tracked here
      as a legitimate product backlog item rather than a launch blocker for
      a simulation.
- [ ] **Real customer outreach / marketing** — not applicable.
- [ ] **Database backup + restore runbook, tested restore** — a real
      production requirement (the disaster drill above only tested
      *availability* through a restart, not *durability* through a
      restore-from-backup). Worth doing as a pure infrastructure exercise
      independent of the business questions above — tracked as a follow-up,
      not done in this session.
- [ ] **Uptime monitor + error alerting** (e.g. a pinger + Sentry/rollbar
      equivalent) — same category as backups: a legitimate infra gap,
      independent of the "real business" question, not yet built.

## What "soft launch" means for this project

Days 35–36 are a **documented simulation**: the full pipeline is exercised
end to end with test data and test Razorpay keys, under the same rigor as
every other week (real infra, real assertions, no mocking of the parts that
matter), and the results are written up honestly (see `METRICS.md`). No
claim is made anywhere that this is live for real customers.
