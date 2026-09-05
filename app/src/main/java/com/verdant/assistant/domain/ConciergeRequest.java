package com.verdant.assistant.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ConciergeRequest(String query, String destinationPincode, BigDecimal budget,
                                LocalDate requestedDeliveryDate, String userSubjectId) {

    public static ConciergeRequest of(String query, String destinationPincode) {
        return new ConciergeRequest(query, destinationPincode, null, null, null);
    }

    public static ConciergeRequest of(String query, String destinationPincode, LocalDate requestedDeliveryDate) {
        return new ConciergeRequest(query, destinationPincode, null, requestedDeliveryDate, null);
    }
}
