package com.verdant.orders.allocation;

import com.verdant.cart.CartLine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AllocationEngine {

    @Value("${verdant.allocation.weight.transit:0.55}")       private double wTransit;
    @Value("${verdant.allocation.weight.quality:0.25}")       private double wQuality;
    @Value("${verdant.allocation.weight.consolidation:0.15}") private double wConsolidation;
    @Value("${verdant.allocation.weight.cost:0.05}")          private double wCost;

    private final CandidateFinder candidates;

    public AllocationEngine(CandidateFinder candidates) {
        this.candidates = candidates;
    }

    public AllocationPlan plan(List<CartLine> lines, String destinationPincode) {
        Map<UUID, List<Allocation>> bySeller = new LinkedHashMap<>();
        List<UnfulfillableLine> unfulfillable = new ArrayList<>();

        for (CartLine line : lines) {
            List<Candidate> eligible = candidates.find(line, destinationPincode).stream()
                .filter(c -> c.hasStock(line.quantity()))
                .filter(Candidate::canReceiveOrders)
                .filter(c -> c.transitDays() <= c.maxSafeTransitDays()) // per-plant limit - arriving dead in one parcel is worse than arriving alive in two
                .toList();

            if (eligible.isEmpty()) {
                unfulfillable.add(new UnfulfillableLine(line.listingId(),
                    candidates.productNameFor(line.listingId()),
                    "No seller can currently deliver this within a safe transit window"));
                continue;
            }

            Candidate chosen = eligible.stream()
                .max(Comparator
                    .comparingDouble((Candidate c) -> score(c, bySeller.keySet()))
                    .thenComparing(Candidate::sellerId)) // deterministic tiebreak - without this, allocation can't be reproduced when a seller complains
                .orElseThrow();

            bySeller.computeIfAbsent(chosen.sellerId(), k -> new ArrayList<>())
                .add(Allocation.of(line, chosen, score(chosen, bySeller.keySet())));
        }

        return new AllocationPlan(bySeller, unfulfillable);
    }

    private double score(Candidate c, Set<UUID> sellersAlreadyUsed) {
        double transit = 1.0 - ((double) c.transitDays() / c.maxSafeTransitDays());
        double quality = c.sellerQualityScore();
        double consolidation = sellersAlreadyUsed.contains(c.sellerId()) ? 1.0 : 0.0;
        double cost = 1.0 - c.normalizedPrice();

        return wTransit * transit
             + wQuality * quality
             + wConsolidation * consolidation
             + wCost * cost;
    }
}
