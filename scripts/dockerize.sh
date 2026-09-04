#!/usr/bin/env bash
set -e
./gradlew :app:bootJar
docker build -t verdant:local -f infra/docker/Dockerfile .
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5433/verdant \
  -e DB_USER=verdant -e DB_PASSWORD=verdant \
  -e REDIS_HOST=host.docker.internal -e REDIS_PORT=6379 \
  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \
  -e OIDC_ISSUER_URI=http://host.docker.internal:8081/realms/verdant \
  -e S3_BUCKET=verdant-images -e AWS_REGION=ap-south-1 \
  -e EMBEDDING_MODEL_URI=https://huggingface.co/sentence-transformers/all-mpnet-base-v2/resolve/main/onnx/model.onnx \
  -e EMBEDDING_TOKENIZER_URI=https://huggingface.co/sentence-transformers/all-mpnet-base-v2/resolve/main/tokenizer.json \
  -e RAZORPAY_KEY_ID=rzp_live_dummy \
  -e RAZORPAY_KEY_SECRET=dummy-secret-for-dockerize-smoketest-only \
  -e RAZORPAY_WEBHOOK_SECRET=dummy-webhook-secret-for-dockerize-smoketest-only \
  -e CORS_ALLOWED_ORIGINS=http://localhost:5173 \
  -e SHIPROCKET_EMAIL=dummy@example.com \
  -e SHIPROCKET_PASSWORD=dummy-password-for-dockerize-smoketest-only \
  -e SHIPROCKET_WEBHOOK_API_KEY=dummy-webhook-key-for-dockerize-smoketest-only \
  -e WEATHER_API_KEY=dummy-weather-key-for-dockerize-smoketest-only \
  verdant:local
# Note: RAZORPAY_KEY_ID is deliberately rzp_live_-shaped (not a real key) -
# KeyModeGuard (added Day 35) refuses to boot the prod profile with a
# rzp_test_ key, so a genuinely-test-shaped dummy here would fail this
# smoke test on purpose. A real prod deploy still requires the real live
# key from a proper secrets manager, never a value in this script.
