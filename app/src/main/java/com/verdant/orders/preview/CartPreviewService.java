package com.verdant.orders.preview;

import com.verdant.cart.CartLine;
import com.verdant.common.domain.Money;
import com.verdant.orders.allocation.Allocation;
import com.verdant.orders.allocation.AllocationEngine;
import com.verdant.orders.allocation.AllocationPlan;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class CartPreviewService {

    private final AllocationEngine engine;
    private final ShippingProperties shipping;

    public CartPreviewService(AllocationEngine engine, ShippingProperties shipping) {
        this.engine = engine;
        this.shipping = shipping;
    }

    public CartPreviewResponse preview(List<CartLine> items, String destinationPincode) {
        AllocationPlan plan = engine.plan(items, destinationPincode);
        Money perShipmentFee = Money.of(shipping.perShipmentFee());

        List<ShipmentPreview> shipments = plan.shipments().stream()
            .map(group -> toShipmentPreview(group, perShipmentFee))
            .toList();

        // Charged per shipment, but capped in total - a cart that splits
        // into three shipments must never cost the buyer three separate
        // shipping fees; the platform absorbs the difference rather than
        // let a split (which is for the plants' own benefit, not the
        // buyer's convenience) tank conversion.
        Money rawTotal = perShipmentFee.times(shipments.size());
        Money platformCap = Money.of(shipping.platformCap());
        Money totalShippingCost = rawTotal.amount().compareTo(platformCap.amount()) > 0 ? platformCap : rawTotal;

        List<UnfulfillableLinePreview> unfulfillable = plan.unfulfillable().stream()
            .map(u -> new UnfulfillableLinePreview(u.listingId(), u.productName(), u.reason()))
            .toList();

        return new CartPreviewResponse(shipments, unfulfillable, totalShippingCost, plan.requiresBuyerConfirmation());
    }

    private ShipmentPreview toShipmentPreview(List<Allocation> group, Money perShipmentFee) {
        UUID sellerId = group.get(0).sellerId();
        int transitDays = group.stream().mapToInt(Allocation::transitDays).max().orElse(0);
        List<AllocatedLinePreview> lines = group.stream()
            .map(a -> new AllocatedLinePreview(a.listingId(), a.productName(), a.quantity()))
            .toList();
        return new ShipmentPreview(sellerId, lines, transitDays, LocalDate.now().plusDays(transitDays), perShipmentFee);
    }
}
