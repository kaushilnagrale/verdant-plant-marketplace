#!/usr/bin/env bash
# Real p50/p95/max for "how long from a listing change to it landing in
# OpenSearch" - the number METRICS.md states as the actual consistency
# budget (Day 52.2). Polls the real-time GET api (/_doc/$ID), same as
# ReindexWorker's own search.index.lag metric (actuator/metrics/search.index.lag)
# measures - "indexed", not "visible to _search" (a separate, smaller,
# additional refresh-interval delay on top of this number).
#
# REAL GAP vs. the runbook's own version, found by running this once and
# watching it hang forever: a generic title like "Lag Test 1" never gets
# auto-mapped to a canonical product (Week 3's own real mapping
# confidence threshold correctly leaves it UNMAPPED), and an unmapped
# listing is deliberately excluded from the index entirely
# (ProjectionQueryRepository's own INNER JOIN) - so /_doc/$ID never
# becomes found, and the until loop below spins forever. A real,
# confidently-mappable plant name (every listing sharing the same one is
# fine - this measures pipeline latency, not title uniqueness) lets each
# one flow through the REAL end-to-end path this metric is meant to
# cover: create -> async auto-map -> outbox -> Kafka -> projector ->
# reindex queue -> OpenSearch.
set -e
TOKEN=${TOKEN:-$(./scripts/token.sh seller-a)}

# `date +%s%3N` is GNU-only - found by running this for real on this
# project's own macOS dev machine, where BSD date has no %N at all and
# silently prints "3N" as literal text instead of erroring. python3 (already
# a dependency of nothing here, but present on both this Mac and any real
# Linux box) gives a genuinely portable millisecond clock instead.
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

for i in $(seq 1 20); do
  START=$(now_ms)
  ID=$(curl -s -X POST localhost:8080/v1/listings -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' \
        -d '{"rawTitle":"Money Plant","priceAmount":"100.00"}' | jq -r .id)
  until curl -s "localhost:9200/listings/_doc/$ID" | jq -e .found > /dev/null 2>&1; do sleep 0.05; done
  echo $(( $(now_ms) - START ))
done | sort -n | awk '{a[NR]=$1} END {print "p50:", a[int(NR*0.5)], "p95:", a[int(NR*0.95)], "max:", a[NR]}'
