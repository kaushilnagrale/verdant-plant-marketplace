package com.verdant.common.outbox;

import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

// "!test | search-test" rather than a plain "!test" (Week 9): search
// tests (SearchIntegrationTestBase) run with a real Kafka-protocol broker
// (Testcontainers) and genuinely need the real publisher, unlike every
// other test in this project - "search-test" is a second profile that
// base class activates on top of "test", specifically so this can be
// singled out without touching what every other test gets.
@Component
@Profile("!test | search-test")
public class KafkaEventPublisher implements EventPublisher {

    /**
     * Boot's auto-configured KafkaTemplate bean is declared
     * KafkaTemplate<Object, Object> regardless of the configured
     * key/value serializers - those control runtime (de)serialization, not
     * the bean's declared generic signature, and Spring's DI matches
     * generics by declared type. Requesting KafkaTemplate<String, String>
     * here fails to find any candidate; this raw-object-typed field is what
     * actually matches, our String args just get passed as Objects (fine
     * at runtime under erasure, since the producer's serializers are
     * StringSerializer either way).
     */
    private final KafkaTemplate<Object, Object> kafka;

    public KafkaEventPublisher(KafkaTemplate<Object, Object> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publish(String topic, String key, String payload) throws Exception {
        kafka.send(topic, key, payload).get(5, TimeUnit.SECONDS);
    }
}
