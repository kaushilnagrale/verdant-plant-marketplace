# See prometheus.Dockerfile's own comment - same real host file-sharing
# limitation, same fix (COPY at build time, not a runtime bind-mount).
FROM grafana/grafana:12.3.0
COPY grafana-provisioning /etc/grafana/provisioning
COPY dashboards /var/lib/grafana/dashboards
