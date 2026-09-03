package com.verdant.common.alerting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * No real PagerDuty/Slack/on-call integration exists for this project (see
 * LAUNCH_CHECKLIST.md - genuinely missing, not faked here either). This is
 * real in the sense that matters for now: every WARN-level log line is a
 * real signal a real ops process could alert on by tailing/shipping logs,
 * and the bounded in-memory capture lets tests assert an alert genuinely
 * fired for the right reason, not just that a log line was printed
 * somewhere. Swap the log-only path for a real paging integration without
 * touching any caller - this interface is the seam.
 */
@Component
public class Alerting {

    private static final Logger log = LoggerFactory.getLogger("verdant.alerts");
    private static final int MAX_CAPTURED = 200;

    private final List<String> captured = new CopyOnWriteArrayList<>();

    public void raise(String message) {
        log.warn("ALERT: {}", message);
        captured.add(message);
        while (captured.size() > MAX_CAPTURED) {
            captured.remove(0);
        }
    }

    public List<String> captured() {
        return Collections.unmodifiableList(captured);
    }

    public void clear() {
        captured.clear();
    }
}
