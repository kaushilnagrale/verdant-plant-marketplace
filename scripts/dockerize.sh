#!/usr/bin/env bash
set -e
./gradlew :app:bootJar
docker build -t verdant:local -f infra/docker/Dockerfile .
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5433/verdant \
  -e DB_USER=verdant -e DB_PASSWORD=verdant \
  -e REDIS_HOST=host.docker.internal -e REDIS_PORT=6379 \
  -e OIDC_ISSUER_URI=http://host.docker.internal:8081/realms/verdant \
  -e S3_BUCKET=verdant-images -e AWS_REGION=ap-south-1 \
  verdant:local
