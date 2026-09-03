package com.verdant.orders.saga;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory only - no real courier integration exists yet. Active in every profile since there is no alternative implementation to switch to. */
@Component
public class StubLogisticsGateway implements LogisticsGateway {

    private final Map<UUID, String> booked = new ConcurrentHashMap<>();
    private final AtomicInteger cancelCallCount = new AtomicInteger(0);

    @Override
    public String book(UUID orderId) {
        String reference = "SHIP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        booked.put(orderId, reference);
        return reference;
    }

    @Override
    public void cancel(UUID orderId) {
        cancelCallCount.incrementAndGet();
        booked.remove(orderId);
    }

    public boolean isBooked(UUID orderId) {
        return booked.containsKey(orderId);
    }

    public int cancelCallCount() {
        return cancelCallCount.get();
    }

    public void reset() {
        booked.clear();
        cancelCallCount.set(0);
    }
}
