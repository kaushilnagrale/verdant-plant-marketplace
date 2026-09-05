package com.verdant.finance;

import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves PayoutStatementController's own authorization decision, given
 * whatever authorities/subject Spring Security has already resolved by
 * the time a request reaches it - subject-based lookups use a real
 * subject_id against a real seeded seller_users row (matching the JWT
 * post-processor's normal use elsewhere in this project); the
 * PLATFORM_ADMIN case supplies its authority directly via .authorities(),
 * since the jwt() post-processor builds its Authentication itself and
 * never runs a fabricated token through SecurityConfig's real
 * keycloakRealmRoleConverter. That claim -> authority mapping is real
 * Keycloak wiring, not this controller's logic, and is verified
 * separately against the real running app with a real issued token.
 */
@AutoConfigureMockMvc
class PayoutAccessTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private UUID sellerId;
    private UUID otherSellerId;
    private UUID cycleId;
    private String ownerSubject;
    private String staffSubject;
    private String otherOwnerSubject;

    @BeforeEach
    void seed() {
        sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Payout Access Test Nursery " + UUID.randomUUID());
        otherSellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Other Nursery " + UUID.randomUUID());

        ownerSubject = "sub-owner-" + UUID.randomUUID();
        staffSubject = "sub-staff-" + UUID.randomUUID();
        otherOwnerSubject = "sub-other-owner-" + UUID.randomUUID();

        jdbc.update("INSERT INTO seller_users (seller_id, subject_id, role) VALUES (?, ?, 'OWNER')", sellerId, ownerSubject);
        jdbc.update("INSERT INTO seller_users (seller_id, subject_id, role) VALUES (?, ?, 'STAFF')", sellerId, staffSubject);
        jdbc.update("INSERT INTO seller_users (seller_id, subject_id, role) VALUES (?, ?, 'OWNER')", otherSellerId, otherOwnerSubject);

        cycleId = jdbc.queryForObject("""
            INSERT INTO payout_cycles (seller_id, period_start, period_end, status, gross_amount, withheld_amount, net_amount, idempotency_key)
            VALUES (?, ?, ?, 'PENDING_APPROVAL', 349.00, 0, 349.00, ?) RETURNING id
            """, UUID.class, sellerId, LocalDate.now().minusDays(7), LocalDate.now(), "payout:" + UUID.randomUUID());
    }

    private String statementUrl() {
        return "/v1/sellers/" + sellerId + "/payouts/" + cycleId + "/statement";
    }

    @Test
    void sellerStaff_cannotViewPayoutStatement() throws Exception {
        mockMvc.perform(get(statementUrl()).with(jwt().jwt(j -> j.subject(staffSubject))))
            .andExpect(status().isForbidden());
    }

    @Test
    void sellerOwner_cannotViewAnotherSellersStatement() throws Exception {
        // otherOwnerSubject genuinely owns a DIFFERENT seller - real OWNER role, wrong seller.
        mockMvc.perform(get(statementUrl()).with(jwt().jwt(j -> j.subject(otherOwnerSubject))))
            .andExpect(status().isForbidden());
    }

    @Test
    void sellerOwner_canViewTheirOwnStatement() throws Exception {
        mockMvc.perform(get(statementUrl()).with(jwt().jwt(j -> j.subject(ownerSubject))))
            .andExpect(status().isOk());
    }

    @Test
    void platformOps_canViewAnyStatement() throws Exception {
        // .authorities(...) supplies the Authentication's authorities
        // directly, the same way a real request's would already be
        // populated by the time it reaches the controller - the jwt()
        // post-processor builds its Authentication itself and never runs
        // it through SecurityConfig's real keycloakRealmRoleConverter, so
        // setting the raw realm_access claim here would prove nothing
        // about this controller. That claim -> authority mapping is
        // verified separately, against the real running app with a real
        // Keycloak-issued PLATFORM_ADMIN token.
        mockMvc.perform(get(statementUrl()).with(jwt()
                .jwt(j -> j.subject("sub-ops-" + UUID.randomUUID()))
                .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
            .andExpect(status().isOk());
    }
}
