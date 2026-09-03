package com.verdant.orders.preview;

import com.verdant.common.domain.Money;

import java.util.List;

/** The buyer sees this before paying, never after (see AllocationEngine's own doc comment). */
public record CartPreviewResponse(List<ShipmentPreview> shipments, List<UnfulfillableLinePreview> unfulfillable,
                                   Money totalShippingCost, boolean requiresBuyerConfirmation) { }
