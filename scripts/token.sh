#!/usr/bin/env bash
curl -s -X POST http://localhost:8081/realms/verdant/protocol/openid-connect/token \
  -d "client_id=verdant-seller" \
  -d "client_secret=dev-secret" \
  -d "username=${1:-seller-a}" \
  -d "password=password" \
  -d "grant_type=password" | jq -r .access_token
