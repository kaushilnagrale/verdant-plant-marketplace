package com.verdant.orders.saga;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OrderSagaRepository extends JpaRepository<OrderSaga, UUID> {

    @Query("""
        SELECT s FROM OrderSaga s
         WHERE s.nextAttemptAt <= :now AND s.state NOT IN ('COMPLETED', 'FAILED')
         ORDER BY s.nextAttemptAt
        """)
    List<OrderSaga> findReady(@Param("now") Instant now, Pageable pageable);
}
