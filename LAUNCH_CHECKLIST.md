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
- [x] AI assistant guardrails (gift concierge / plant doctor, Week 11) — no
      real LLM or vision provider account exists for this project (same
      stub-gateway treatment as Razorpay/WhatsApp before those were real),
      but the actual point of this feature — that a hallucinated
      recommendation or a fabricated citation never reaches a customer —
      is verified for real: `validate()`/`validateGrounded()` are exercised
      against a model stub deliberately configured to misbehave (invent a
      listing, drop citations, cite an unretrieved chunk, name a specific
      pesticide), not just against well-behaved output. A 40-case golden
      set (`AssistantEvalTest`) holds this to fixed thresholds — citation
      rate 1.00, adversarial hold 1.00, groundedness ≥0.85, outcome
      accuracy ≥0.90 — across a representative spread of real inputs, not
      just the scenarios its own author happened to name.

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
- [x] **Seller vacation mode / inventory pause** — built for real (Week 10,
      `sellers.on_vacation`), enforced as a hard exclusion in the real
      allocation candidate query: a vacationing seller stops receiving new
      orders immediately, while anything already allocated/booked is
      untouched (allocation never re-runs against a settled order).
      Verified against real infrastructure (`SellerAccountControllerTest`,
      `SellerOrderControllerTest`).
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
- [ ] **A real human usability session on the seller app** (`frontend/seller-web`) —
      the runbook's own explicit gate for that week ("sit with her, give no
      instructions, time three tasks") is a genuine in-person session with a
      real person this autonomous build session cannot perform. The app
      itself is real (five working screens against real backend endpoints,
      a real offline queue with a real concurrency bug found and fixed
      while testing it, real build/typecheck/unit-test verification - see
      `frontend/seller-web/README.md`), but no one has actually watched a
      real seller use it yet. Do that before trusting the screen set, the
      copy, or the flow are actually right - "every hesitation is a bug"
      only means something if someone was there to see one.
- [ ] **Native Hindi review of the seller app's translated labels** -
      `frontend/seller-web/src/i18n/hi.json` is standard-dictionary Hindi,
      not reviewed by a native speaker against what a real seller would
      actually say. The runbook's own guidance here is explicit that
      guessing produces an interface that reads as translated rather than
      native - flagged rather than presented as verified.
- [ ] **Real cultural-authority review of gift-concierge occasion content**
      (`content/knowledge/occasion-*.md`, `cultural-*.md`, Week 11) — the
      runbook's own design principle is that the model must never
      generate a cultural or religious claim on its own; that requirement
      is only pushed one level up, not satisfied, by this session writing
      the content instead. Every one of these files is explicitly marked
      `AI-DRAFTED PLACEHOLDER — NOT reviewed or signed off by a real
      cultural authority` in its own frontmatter. Do not ship this content
      to a real customer until a real person with standing to speak on it
      has reviewed it — the retrieval/validation pipeline around it is
      real and tested, but the content itself is not yet trustworthy.
- [ ] **Real festival-calendar verification** (`festival_calendar`, V38,
      Week 11) — the seeded 2026/2027 Raksha Bandhan/Diwali/Holi dates are
      this project's own best real knowledge, not cross-checked against an
      authoritative panchang or calendar service. India's major gifting
      festivals follow a lunisolar calendar and can shift by a day or two
      year to year; verify before using these dates to actually staff or
      stock for a real festival period.

## What "soft launch" means for this project

Days 35–36 are a **documented simulation**: the full pipeline is exercised
end to end with test data and test Razorpay keys, under the same rigor as
every other week (real infra, real assertions, no mocking of the parts that
matter), and the results are written up honestly (see `METRICS.md`). No
claim is made anywhere that this is live for real customers.
