package com.verdant.finance.repository;

import com.verdant.finance.domain.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {
    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);

    /** Proves "exactly once" under concurrency (claims, payouts, Week 8) - a reference is shared by every attempt at resolving/paying the same real-world thing. */
    long countByReference(String reference);
}
