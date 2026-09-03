package com.verdant.outbox;

import com.verdant.common.outbox.EventPublisher;
import com.verdant.common.outbox.OutboxDrainer;
import com.verdant.common.outbox.OutboxEvent;
import com.verdant.common.outbox.OutboxRepository;
import com.verdant.common.outbox.OutboxWriter;
import com.verdant.common.outbox.StubEventPublisher;
import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxTest extends IntegrationTestBase {

    @Autowired private OutboxWriter outboxWriter;
    @Autowired private OutboxRepository outboxRepo;
    @Autowired private OutboxDrainer drainer;
    @Autowired private EventPublisher eventPublisher; // the "test" profile bean IS StubEventPublisher
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;

    private TransactionTemplate tx;
    private StubEventPublisher stub;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        stub = (StubEventPublisher) eventPublisher;
        stub.reset();
    }

    /**
     * No real business flow writes to the outbox yet (that comes later this
     * week), so this simulates one directly with TransactionTemplate: write
     * the event, then force a failure, all inside one transaction - proving
     * the write rolls back with everything else rather than needing its own
     * dedicated business-flow test double.
     */
    @Test
    void eventAndBusinessData_commitAtomically() {
        UUID aggregateId = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            outboxWriter.write("TestAggregate", aggregateId, "TEST_EVENT", Map.of("k", "v"));
            throw new RuntimeException("forced failure after outbox write");
        })).isInstanceOf(RuntimeException.class);

        assertThat(outboxRepo.findByAggregateId(aggregateId)).isEmpty();
    }

    @Test
    void writeOutsideTransaction_isRejected() {
        assertThatThrownBy(() ->
            outboxWriter.write("TestAggregate", UUID.randomUUID(), "TEST_EVENT", Map.of()))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * The real backoff() computes real wall-clock delays (2^attempts
     * seconds), so a tight test loop can't just call drain() repeatedly and
     * expect retries to become due - forcing next_attempt_at back to "now"
     * between calls tests the same retry-and-eventually-succeed behavior
     * without actually waiting out an exponential backoff in a unit test.
     */
    private void makeAllPendingDueNow() {
        jdbc.update("UPDATE outbox SET next_attempt_at = now() WHERE published_at IS NULL");
    }

    @Test
    void failedPublish_isRetriedWithBackoff_notLost() {
        stub.failNextSends(3);
        UUID aggregateId = UUID.randomUUID();
        tx.executeWithoutResult(status ->
            outboxWriter.write("TestAggregate", aggregateId, "TEST_EVENT", Map.of("k", "v")));

        for (int i = 0; i < 5; i++) {
            makeAllPendingDueNow();
            drainer.drain();
        }

        List<OutboxEvent> events = outboxRepo.findByAggregateId(aggregateId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getPublishedAt()).isNotNull();
        assertThat(stub.sendAttempts()).isEqualTo(4); // 3 fail, 1 succeed
    }

    @Test
    void concurrentDrainers_publishEachEventOnce() throws Exception {
        // outbox is a global, unscoped queue (unlike a per-entity balance) -
        // claimBatch() will happily sweep up leftover unpublished rows from
        // other tests/prior runs sharing this reused Testcontainers
        // database too. Track exactly the ids THIS test created and assert
        // on those specifically, rather than the shared stub's raw total -
        // otherwise this test is flaky by construction, not by bad luck.
        List<UUID> aggregateIds = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            UUID aggregateId = UUID.randomUUID();
            aggregateIds.add(aggregateId);
            int index = i;
            tx.executeWithoutResult(status ->
                outboxWriter.write("TestAggregate", aggregateId, "TEST_EVENT", Map.of("i", index)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    drainer.drain();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }
        start.countDown();
        finished.await();
        pool.shutdown();

        List<OutboxEvent> ours = aggregateIds.stream()
            .flatMap(id -> outboxRepo.findByAggregateId(id).stream())
            .toList();
        assertThat(ours).hasSize(50);
        assertThat(ours).allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());

        // published_at alone wouldn't catch a true double-send at the Kafka
        // layer (the UPDATE that sets it is idempotent either way) - check
        // the stub directly saw exactly one send per our own key.
        Set<String> ourKeys = aggregateIds.stream().map(UUID::toString).collect(java.util.stream.Collectors.toSet());
        List<StubEventPublisher.Published> oursSent = stub.sentMessages().stream()
            .filter(p -> ourKeys.contains(p.key()))
            .toList();
        assertThat(oursSent).hasSize(50);
    }

    @Test
    void eventsForSameAggregate_shareAPartitionKey() {
        UUID aggregateId = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            outboxWriter.write("TestAggregate", aggregateId, "EVENT_ONE", Map.of("step", 1));
            outboxWriter.write("TestAggregate", aggregateId, "EVENT_TWO", Map.of("step", 2));
        });

        List<String> keys = outboxRepo.findByAggregateId(aggregateId).stream()
            .map(OutboxEvent::getPartitionKey)
            .distinct()
            .toList();

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).isEqualTo(aggregateId.toString());
    }
}
