package com.verdant.finance.web;

import com.verdant.finance.domain.PayoutStatement;
import com.verdant.finance.service.PayoutCycleRepository;
import com.verdant.identity.SellerUser;
import com.verdant.identity.SellerUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Sellers will dispute a payout, and the statement is the answer - so
 * getting who can SEE it right matters as much as the numbers themselves.
 *
 * Authorization is a manual check, not @PreAuthorize - matches this
 * project's own established pattern everywhere else (SellerOrderController's
 * JWT-subject lookup), and @EnableMethodSecurity isn't enabled anywhere in
 * this codebase. PLATFORM_ADMIN is a real Keycloak realm role (created for
 * this feature, checked via SecurityConfig's own JWT authority mapping -
 * see its doc comment for the gap that mapping closes); seller access is
 * OWNER-only, per this project's own seller_users.role column, and scoped
 * to exactly the seller named in the path - a seller can never view
 * another seller's payout by editing the URL.
 */
@RestController
public class PayoutStatementController {

    private final PayoutCycleRepository cycles;
    private final SellerUserRepository sellerUsers;

    public PayoutStatementController(PayoutCycleRepository cycles, SellerUserRepository sellerUsers) {
        this.cycles = cycles;
        this.sellerUsers = sellerUsers;
    }

    @GetMapping("/v1/sellers/{sellerId}/payouts/{cycleId}/statement")
    public PayoutStatement statement(Authentication authentication, @AuthenticationPrincipal Jwt jwt,
                                      @PathVariable UUID sellerId, @PathVariable UUID cycleId) {
        if (!isPlatformAdmin(authentication)) {
            requireSellerOwnerOf(jwt, sellerId);
        }
        return PayoutStatement.from(cycles.findById(cycleId));
    }

    private boolean isPlatformAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
            .anyMatch(a -> "PLATFORM_ADMIN".equals(a.getAuthority()));
    }

    private void requireSellerOwnerOf(Jwt jwt, UUID sellerId) {
        SellerUser caller = sellerUsers.findBySubjectId(jwt.getSubject())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No seller mapped to this account"));

        // Seller staff must not see payouts - only the owner (Day 45.1).
        if (!"OWNER".equals(caller.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the seller owner may view payout statements");
        }
        if (!caller.getSellerId().equals(sellerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot view another seller's payout statement");
        }
    }
}
