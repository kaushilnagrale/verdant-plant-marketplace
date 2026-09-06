# Config baked in at build time via COPY, not a host bind-mount at
# runtime - found for real, not hypothetically: this Mac's Docker
# Desktop file-sharing allowlist doesn't cover ~/Desktop (everything
# else in docker-compose.yml uses named volumes or no host mount at
# all, so this is the first config here to actually hit that limit).
# docker build's COPY reads the build context by streaming it from the
# host CLI, an entirely different path from a bind-mount's live
# virtiofs passthrough, so it works regardless of that allowlist - same
# real reasoning as dockerize.sh's own app.jar COPY working around a
# different host-path limitation.
FROM prom/prometheus:v3.7.3
COPY prometheus.yml /etc/prometheus/prometheus.yml
