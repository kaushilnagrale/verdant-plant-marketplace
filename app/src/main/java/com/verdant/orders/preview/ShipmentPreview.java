package com.verdant.orders.preview;

import com.verdant.common.domain.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ShipmentPreview(UUID sellerId, List<AllocatedLinePreview> lines,
                               int transitDays, LocalDate estimatedDelivery, Money shippingCost) { }
