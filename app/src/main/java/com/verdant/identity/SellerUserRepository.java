package com.verdant.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface SellerUserRepository extends JpaRepository<SellerUser, UUID> {
    Optional<SellerUser> findBySubjectId(String subjectId);
}
