package com.verdant.logistics;

import com.verdant.logistics.domain.SizeTierDimensions.Dimensions;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Profile("test")
public class StubCourierGateway implements CourierGateway {

    private final AtomicInteger createCalls = new AtomicInteger(0);
    private final AtomicBoolean timeoutNext = new AtomicBoolean(false);
    private final Set<String> awbsIssued = ConcurrentHashMap.newKeySet();

    @Override
    public Booking createShipment(String idempotencyKey, String originPincode, String destinationPincode,
                                   int billableGrams, Dimensions dimensions, List<String> labels) {
        createCalls.incrementAndGet();
        if (timeoutNext.compareAndSet(true, false)) {
            throw new RuntimeException("Simulated courier API timeout");
        }
        String awb = "AWB" + UUID.randomUUID().toString().substring(0, 10).toUpperCase().replace("-", "");
        awbsIssued.add(awb);
        return new Booking("Stub Courier", awb, "https://stub.example/label/" + awb);
    }

    /** Makes the next createShipment() call throw, simulating a real API timeout - booking-retry tests. */
    public void timeoutOnNextCall() {
        timeoutNext.set(true);
    }

    public int createCalls() {
        return createCalls.get();
    }

    public int distinctAwbsIssued() {
        return awbsIssued.size();
    }

    public void reset() {
        createCalls.set(0);
        timeoutNext.set(false);
        awbsIssued.clear();
    }
}
