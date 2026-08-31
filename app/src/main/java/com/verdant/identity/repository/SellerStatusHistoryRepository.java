package com.verdant.identity.repository;

import com.verdant.identity.domain.SellerStatus;
import com.verdant.identity.domain.SellerStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface SellerStatusHistoryRepository extends JpaRepository<SellerStatusHistory, Long> {
    List<SellerStatusHistory> findBySellerId(UUID sellerId);

    default void record(UUID sellerId, SellerStatus from, SellerStatus to, String actorId, String reason) {
        save(new SellerStatusHistory(sellerId, from, to, actorId, reason));
    }
}
