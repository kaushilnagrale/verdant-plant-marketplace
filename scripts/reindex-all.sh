#!/usr/bin/env bash
# Blue-green reindex: build a brand new index under the real mapping,
# backfill every listing into it, and only flip the "listings" alias once
# the count matches - never rebuild the live alias in place. See
# BackfillController/BackfillService and infra/opensearch/listings-index.json.
set -e
NEW_INDEX="listings-v$(date +%Y%m%d%H%M)"
OPS_TOKEN=${OPS_TOKEN:-$(./scripts/token.sh ops-1)}

curl -s -X PUT "localhost:9200/$NEW_INDEX" \
  -H 'Content-Type: application/json' -d @infra/opensearch/listings-index.json

curl -s -X POST localhost:8080/v1/admin/search/backfill \
  -H "Authorization: Bearer $OPS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"targetIndex\":\"$NEW_INDEX\"}"

echo
echo "Backfill started into $NEW_INDEX (runs in the background - see BackfillService's own log line for real completion). Compare counts before flipping:"
echo
echo "  vpsql -At -c \"SELECT count(*) FROM listings;\""
echo "  curl -s \"localhost:9200/$NEW_INDEX/_count\" | jq .count"
echo
echo "Once they match, flip the alias (never rebuild 'listings' in place - this swap is what makes a mapping change downtime-free):"
echo
echo "curl -X POST localhost:9200/_aliases -H 'Content-Type: application/json' -d '{\"actions\":[
  {\"remove\":{\"index\":\"*\",\"alias\":\"listings\"}},
  {\"add\":{\"index\":\"'"$NEW_INDEX"'\",\"alias\":\"listings\"}}]}'"
