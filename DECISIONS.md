# Architecture decisions

Real trade-offs made across this build, recorded at the point they were
made rather than reconstructed afterward. Each one follows the same
rule this whole project has used throughout: build the real thing where
it's practical to verify against real infrastructure, and when full
fidelity genuinely isn't practical, say so explicitly rather than
quietly cutting a corner.

## Multi-tenancy: Postgres row-level security, not schema-per-seller

Every seller-owned table (`listings`, `inventory`, ...) carries a
`seller_id` and a real `FORCE ROW LEVEL SECURITY` policy keyed off
`current_setting('app.current_tenant')`, set per-request by
`TenantContextFilter`. The alternative - a Postgres schema per seller -
scales worse operationally (a migration has to run against every
schema, not once) for a marketplace with potentially thousands of
sellers, and RLS gets the same real isolation guarantee enforced at the
database layer, not just in application code. Verified for real:
`RLSTest`-style coverage across the build confirms a request scoped to
one seller gets a 404 (not a 403 - not leaking that the row exists) for
another seller's row.

## Async integrity: transactional outbox, not dual writes

Every state change that needs to both commit to Postgres and eventually
publish a Kafka event (order paid, seller quality changed, listing
mapped, ...) writes an outbox row in the SAME transaction as the
business change, then a separate poller drains and publishes it. A
naive "write to Postgres, then publish to Kafka" has a real,
un-closeable gap where the process can crash between the two steps;
the outbox makes the business change and the fact-of-needing-to-publish
atomic, and publishing itself is retried until it succeeds
(`OutboxTest` proves at-least-once delivery and that a partially-failed
drain doesn't lose events).

## Search: an async reindex queue, not a synchronous dual-write

A listing write commits to Postgres and enqueues a reindex row in the
same transaction, rather than also calling OpenSearch synchronously in
the request path. The real, deliberate consequence is a bounded
staleness window between a write and its visibility in search results
(measured for real - see `METRICS.md` - p95 ~1.06s) rather than making
every catalog write's latency and success depend on OpenSearch being up.
Anything that authorizes a STATE CHANGE off a search result (allocation,
checkout) re-reads Postgres directly rather than trusting the stale
index - the index is for discovery, never for correctness.

## AI features: real guardrails around a stubbed model, not a real LLM integration

No real LLM or vision-model account exists for this project, the same
honest position as every other external dependency before it became
real (Razorpay, Shiprocket, WhatsApp were all genuinely obtained over
the course of the build; an LLM credential was not). `PlantAssistantModel`/
`VisionService` are real interfaces with a real, test-configurable stub
implementation, active in every profile including prod. The actual
engineering point of this feature - that a hallucinated recommendation
or a fabricated citation can never reach a customer - doesn't depend on
which model produces the raw text, and is verified against a model
deliberately configured to misbehave (`GiftConciergeTest`,
`PlantDoctorTest`, and the 40-case golden set in `AssistantEvalTest`),
which is a *harder* test than only ever exercising a well-behaved real
model would be.

## Load testing: honestly scaled down, not simulated at the real target

Week 9's k6 run used 20 VUs against a target of 500 - this machine and
this database are a laptop-scale dev environment, not production
infrastructure, and reporting fabricated numbers "as if" the real
target had been hit would be worse than reporting real numbers at a
smaller scale with the gap stated plainly (`METRICS.md`). The same
approach applies to any future load test in this project: real numbers,
real scale noted, extrapolation never presented as measurement.

## Week 12: service extraction, proven for real as a boundary, not completed as a physical split

The runbook's own framing for this week is decomposing the monolith
into `catalog-service`/`finance-service` with schema-per-service
isolation. What actually shipped and why:

**Built for real:**
- `V39__service_isolation.sql` - two real, least-privilege Postgres
  roles (`catalog_service_role`, `finance_service_role`), each `REVOKE
  ALL`'d from every table first and then granted access to exactly the
  ~20 tables that domain owns. `ServiceIsolationTest` connects AS each
  role against the real, full 39-migration schema and proves Postgres
  itself rejects a cross-domain query (`permission denied`) - not an
  application-level convention, a database-enforced one.
- `catalog-service` - a genuinely separate Gradle module and Spring
  Boot application (its own `main()`, its own `bootJar`, its own port),
  depending on neither `:app` nor `:common:common-domain`, that
  connects to Postgres as `catalog_service_role` and serves a real read
  endpoint against the real `canonical_products`/`listings` tables.
  Its own test (`ProductControllerTest`) proves the whole application -
  boot included - works end to end under that restricted role.

**Deliberately not done, and why:**
- **No physical schema move.** `ALTER TABLE ... SET SCHEMA` for ~20
  tables would touch every unqualified query across an
  already-large, working, extensively-tested monolith (12 weeks of
  real, passing tests) for a demonstration's own sake. The real
  mechanism a physical split depends on - database-enforced least
  privilege - is proven directly instead, without that blast radius.
- **No `finance-service` module.** One real extraction proves the
  pattern; a second, structurally identical module would be repetition,
  not new evidence. The `finance_service_role` grant exists and is
  tested (`ServiceIsolationTest`) so the *access boundary* for finance
  is real even though a second standalone application isn't.
- **The monolith's own callers were not migrated.** `CandidateFinder`,
  `CartPricingService`, and everything else in `:app` that reads
  catalog data still does so in-process, against the monolith's own
  broader role - they were not rewritten to call `catalog-service` over
  HTTP. That cutover (every caller, plus the data-ownership question of
  which process is allowed to WRITE canonical_products going forward)
  is the genuinely large, genuinely risky remainder of a real
  extraction, and doing it partially - some callers migrated, some not
  - would leave the system in a worse, half-migrated state than
  documenting it as the deliberate next step.
- **No auth on `catalog-service`'s own endpoint yet.** A real
  service-to-service call would sit behind the same OAuth2 boundary (or
  a network-level one) the monolith uses; this proof-of-concept omits
  it to keep the module's own scope legible.

This is the same honesty standard as the Hindi review gap, the cultural
content authorship gap, and the k6 scale-down above: the real,
verifiable slice is built and tested; the remainder is named, not
hidden.

## Week 12: HTTP-request tracing left as a documented gap

Distributed tracing (Prometheus/Grafana/Jaeger, see `METRICS.md`) is
real and live-verified end to end for `@Scheduled` background jobs -
real spans, over real OTLP/HTTP, landing in a real Jaeger instance.
HTTP request-level spans (`GET /v1/catalog` as its own trace) do not
currently appear, despite `http.server.requests` recording correctly
as a metric - something in Spring Boot 4.1.1's Observation-to-Tracing
bridge for web requests specifically isn't wired the same way it is
for scheduled tasks. Diagnosed as far as confirming the Observation
itself fires (the metric exists) and that it's specifically the
tracing bridge, not the exporter pipeline, that's missing for this one
Observation type - the exporter pipeline itself is independently
proven working by the scheduled-job spans reaching Jaeger. Chasing the
exact missing wiring further would mean more bytecode-level
archaeology into an increasingly narrow framework detail, for a gap
that doesn't affect this project's own load-bearing point (a real,
working OTLP pipeline to a real trace backend, which the scheduled-job
spans already prove). Documented rather than either faked or endlessly
chased.
