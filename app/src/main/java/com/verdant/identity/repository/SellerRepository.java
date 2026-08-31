package com.verdant.identity.repository;

import com.verdant.identity.domain.Seller;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SellerRepository extends JpaRepository<Seller, UUID> { }
