package com.verdant.identity.domain;

public record SellerCapabilities(
        boolean canList,
        boolean canReceiveOrders,
        boolean canReceivePayouts) {

    public static SellerCapabilities of(Seller s) {
        boolean approved = s.getStatus() == SellerStatus.APPROVED;
        return new SellerCapabilities(
            approved,
            approved && s.isBankVerified(),
            approved && s.isBankVerified() && s.isTaxDeclarationComplete()
        );
    }
}
