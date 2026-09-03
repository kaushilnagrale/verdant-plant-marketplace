package com.verdant.common.outbox;

/**
 * Not defined in the runbook (which uses KafkaTemplate directly in the
 * drainer). Abstracted the same way PaymentGateway was: a real Kafka-backed
 * implementation for local/prod, a stub for the automated test suite - a
 * real broker in the loop for every test run would be slow, and the
 * runbook's own tests need precise failure injection (failNextSends) that a
 * real broker can't give on demand.
 */
public interface EventPublisher {
    /** Publishes payload to topic under key. Throws on failure - the caller decides retry/backoff. */
    void publish(String topic, String key, String payload) throws Exception;
}
