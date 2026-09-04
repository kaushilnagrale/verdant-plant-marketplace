package com.verdant.logistics.service;

import com.verdant.common.flags.FeatureFlags;
import com.verdant.logistics.CourierGateway;
import com.verdant.logistics.ShipmentLabels;
import com.verdant.logistics.domain.SizeTierDimensions;
import com.verdant.logistics.domain.SizeTierDimensions.Dimensions;
import com.verdant.logistics.domain.WeightCalculator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Not @Transactional as one unit, deliberately - plain auto-committing
 * jdbc.update() calls, same shape as StubLogisticsGateway/ReservationService
 * elsewhere in this project. The claim below needs to survive independently
 * of whether the courier call that follows succeeds or fails (a rolled-back
 * transaction would undo the claim along with everything else, which is
 * exactly the local self-invocation/REQUIRES_NEW class of problem hit
 * repeatedly this project - avoided here by never wrapping the two in one
 * transaction to begin with).
 */
@Service
public class ShipmentBookingService {

    private final JdbcTemplate jdbc;
    private final CourierGateway courier;
    private final FeatureFlags flags;
    private final WeightCalculator weightCalculator;

    public ShipmentBookingService(JdbcTemplate jdbc, CourierGateway courier,
                                   FeatureFlags flags, WeightCalculator weightCalculator) {
        this.jdbc = jdbc;
        this.courier = courier;
        this.flags = flags;
        this.weightCalculator = weightCalculator;
    }

    public void book(UUID shipmentId) {
        Map<String, Object> shipment = jdbc.queryForMap(
            "SELECT order_id, seller_id, origin_pincode, destination_pincode, awb_number FROM shipments WHERE id = ?",
            shipmentId);

        if (shipment.get("awb_number") != null) {
            return; // already booked - true idempotency, no claim needed
        }

        if (!flags.get("shiprocket_booking").active()) {
            // Manual dispatch fallback - a human books this with the
            // courier directly, outside this system. Deliberately a total
            // no-op: no claim taken (a claim here would permanently block
            // a real attempt if the flag gets turned on later for this
            // exact shipment - found while wiring this into the live
            // order flow, not assumed), and no status change (status stays
            // 'BOOKED', the same value the saga already set - a real,
            // previously-invisible bug: this used to write 'READY' here,
            // which SellerOrderService.markPacked() doesn't recognize
            // (guards on status = 'BOOKED'), so real orders would have
            // gotten permanently stuck un-packable the moment automatic
            // dispatch orchestration called this with the flag off, which
            // is the default).
            return;
        }

        // Claim key: our own idempotency key AND (once real booking runs)
        // Shiprocket's own order_id, so a retry after a timeout can never
        // create two pickups - either locally (the claim below) or on the
        // courier's own side (Shiprocket sees the same order_id twice and
        // resolves to the same booking rather than a duplicate).
        String key = "book:" + shipmentId;
        int claimed = jdbc.update(
            "UPDATE shipments SET booking_idempotency_key = ? WHERE id = ? AND booking_idempotency_key IS NULL",
            key, shipmentId);
        if (claimed == 0) {
            return; // someone else is already handling this shipment right now
        }

        UUID orderId = (UUID) shipment.get("order_id");
        UUID sellerId = (UUID) shipment.get("seller_id");
        String originPincode = (String) shipment.get("origin_pincode");
        String destinationPincode = (String) shipment.get("destination_pincode");

        try {
            WeightCalculator.Weight weight = computeWeight(orderId, sellerId);
            Dimensions dims = parcelDimensions(orderId, sellerId);

            var booking = courier.createShipment(key, originPincode, destinationPincode,
                weight.billableGrams(), dims, ShipmentLabels.fragileLivePlant());

            jdbc.update("""
                UPDATE shipments
                   SET courier = ?, awb_number = ?, label_url = ?,
                       dead_weight_grams = ?, volumetric_grams = ?, billable_grams = ?,
                       status = 'BOOKED'
                 WHERE id = ?
                """, booking.courier(), booking.awb(), booking.labelUrl(),
                weight.deadGrams(), weight.volumetricGrams(), weight.billableGrams(), shipmentId);
        } catch (RuntimeException e) {
            // Release the claim so a retry (this method called again,
            // sequentially, after the caller catches this) can actually
            // reach the courier again - unlike a permanently-held claim,
            // which would silently no-op every retry forever.
            jdbc.update("UPDATE shipments SET booking_idempotency_key = NULL WHERE id = ?", shipmentId);
            throw e;
        }
    }

    /** Dead weight sums across every item in this seller's portion of the order - a consolidated box weighs what's actually in it. */
    private WeightCalculator.Weight computeWeight(UUID orderId, UUID sellerId) {
        Dimensions dims = parcelDimensions(orderId, sellerId);
        int totalDeadGrams = itemDimensions(orderId, sellerId).stream()
            .mapToInt(i -> i.dimensions().deadWeightGrams() * i.quantity())
            .sum();
        return weightCalculator.calculate(dims.lengthCm(), dims.widthCm(), dims.heightCm(), totalDeadGrams);
    }

    /**
     * The largest single item's footprint stands in for the consolidated
     * parcel's box size - a real system would compute actual packed-box
     * dimensions; this project has no per-listing dimensions to pack from
     * (see SizeTierDimensions), so the biggest item's approximate size is
     * the closest honest stand-in rather than a naive sum of unrelated boxes.
     */
    private Dimensions parcelDimensions(UUID orderId, UUID sellerId) {
        return itemDimensions(orderId, sellerId).stream()
            .map(Item::dimensions)
            .max(java.util.Comparator.comparingInt(d -> d.lengthCm() * d.widthCm() * d.heightCm()))
            .orElseThrow(() -> new IllegalStateException("Shipment has no order items: order=" + orderId + " seller=" + sellerId));
    }

    private List<Item> itemDimensions(UUID orderId, UUID sellerId) {
        return jdbc.query("""
            SELECT oi.quantity, cp.size_tier
              FROM order_items oi
              JOIN listings l ON l.id = oi.listing_id
              JOIN canonical_products cp ON cp.id = l.canonical_product_id
             WHERE oi.order_id = ? AND oi.seller_id = ?
            """, (rs, rowNum) -> new Item(rs.getInt("quantity"),
                SizeTierDimensions.forTier(rs.getString("size_tier"))), orderId, sellerId);
    }

    private record Item(int quantity, Dimensions dimensions) { }
}
