package com.verdant.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class OutboxDrainer {

    private static final Logger log = LoggerFactory.getLogger(OutboxDrainer.class);
    private static final int MAX_ATTEMPTS = 12;

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final TransactionTemplate tx;

    public OutboxDrainer(OutboxRepository outbox, EventPublisher publisher, PlatformTransactionManager txManager) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${verdant.outbox.interval-ms:500}")
    public void drain() {
        tx.executeWithoutResult(status -> {
            List<OutboxEvent> batch = outbox.claimBatch(100); // FOR UPDATE SKIP LOCKED
            for (OutboxEvent e : batch) {
                try {
                    publisher.publish(topicFor(e.getEventType()), e.getPartitionKey(), e.getPayload().toString());
                    outbox.markPublished(e.getId(), Instant.now());
                } catch (Exception ex) {
                    int attempts = e.getAttempts() + 1;
                    if (attempts >= MAX_ATTEMPTS) {
                        outbox.markDead(e.getId(), ex.getMessage());
                        log.error("Outbox event {} exhausted retries after {} attempts: {}",
                            e.getId(), attempts, ex.getMessage());
                    } else {
                        outbox.scheduleRetry(e.getId(), backoff(attempts), ex.getMessage());
                    }
                }
            }
        });
    }

    private String topicFor(String eventType) {
        return "verdant." + eventType.toLowerCase();
    }

    private Instant backoff(int attempts) {
        long seconds = Math.min(300, (long) Math.pow(2, attempts)); // capped exponential
        return Instant.now().plus(Duration.ofSeconds(seconds));
    }
}
