package com.verdant.common.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public OutboxWriter(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    /**
     * MANDATORY propagation: this must run inside the caller's transaction.
     * If it ever starts its own, the whole guarantee (event and business
     * data commit atomically, or neither does) is void - so calling this
     * outside a transaction fails loudly and immediately rather than
     * silently breaking atomicity.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        outbox.save(new OutboxEvent(
            aggregateType, aggregateId, eventType,
            aggregateId.toString(), // partition key: ordering guaranteed per aggregate
            json.valueToTree(payload)));
    }
}
