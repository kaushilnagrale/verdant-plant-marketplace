package com.verdant.identity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "seller_users")
public class SellerUser {

    @Id
    private UUID id;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "subject_id", nullable = false)
    private String subjectId;

    @Column(name = "role", nullable = false)
    private String role;

    protected SellerUser() { }

    public UUID getSellerId() { return sellerId; }
}
