package com.verdant.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sellers")
public class Seller {

    @Id @GeneratedValue private UUID id;
    private String businessName;

    @Enumerated(EnumType.STRING)
    private SellerStatus status = SellerStatus.DRAFT;

    private boolean bankVerified;
    private boolean taxDeclarationComplete;
    private String gstin;
    private boolean gstRegistered;
    private int handlingTimeHours = 24;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    protected Seller() { }

    public Seller(String businessName) { this.businessName = businessName; }

    /** The only way status changes. No setter exists. */
    public void transitionTo(SellerStatus next) {
        status.assertCanMoveTo(next);
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public SellerCapabilities capabilities() { return SellerCapabilities.of(this); }

    public UUID getId() { return id; }
    public String getBusinessName() { return businessName; }
    public SellerStatus getStatus() { return status; }
    public boolean isBankVerified() { return bankVerified; }
    public boolean isTaxDeclarationComplete() { return taxDeclarationComplete; }
    public void markBankVerified() { this.bankVerified = true; }
    public void markTaxDeclarationComplete() { this.taxDeclarationComplete = true; }
}
