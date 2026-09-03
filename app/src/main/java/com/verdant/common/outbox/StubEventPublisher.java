package com.verdant.common.outbox;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile("test")
public class StubEventPublisher implements EventPublisher {

    private final List<Published> sent = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger failuresRemaining = new AtomicInteger(0);
    private final AtomicInteger attempts = new AtomicInteger(0);

    /** Makes the next n publish() calls throw, then succeed again - for testing retry/backoff. */
    public void failNextSends(int n) {
        failuresRemaining.set(n);
    }

    public int sendAttempts() {
        return attempts.get();
    }

    public List<Published> sentMessages() {
        return List.copyOf(sent);
    }

    public void reset() {
        sent.clear();
        failuresRemaining.set(0);
        attempts.set(0);
    }

    @Override
    public void publish(String topic, String key, String payload) throws Exception {
        attempts.incrementAndGet();
        if (failuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new RuntimeException("Simulated publish failure");
        }
        sent.add(new Published(topic, key, payload));
    }

    public record Published(String topic, String key, String payload) { }
}
