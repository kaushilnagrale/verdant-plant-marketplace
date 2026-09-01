package com.verdant.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic background sweep: buyers who never finish checkout leave stock
 * locked in a reservation until it expires. This runs regardless of whether
 * anyone is actively browsing that listing, so abandoned holds don't sit
 * unsellable indefinitely.
 */
@Component
public class ReservationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    private final ReservationService reservations;

    public ReservationExpirySweeper(ReservationService reservations) {
        this.reservations = reservations;
    }

    @Scheduled(fixedRate = 60_000)
    public void sweep() {
        int released = reservations.releaseExpired();
        if (released > 0) {
            log.info("Released {} expired reservation(s)", released);
        }
    }
}
