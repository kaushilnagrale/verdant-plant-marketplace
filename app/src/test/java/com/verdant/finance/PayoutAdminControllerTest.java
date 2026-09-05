package com.verdant.finance;

import com.verdant.common.domain.Money;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.finance.service.AccountResolver;
import com.verdant.finance.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Money moves only after PLATFORM_ADMIN approval (Day 47.1) - a seller,
 * even their own OWNER, must never be able to approve or initiate their
 * own payout by calling this endpoint directly.
 */
@AutoConfigureMockMvc
class PayoutAdminControllerTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LedgerService ledger;
    @Autowired private AccountResolver accounts;

    private UUID sellerId;
    private String ownerSubject;
    private final LocalDate periodStart = LocalDate.now().minusDays(7);
    private final LocalDate periodEnd = LocalDate.now();

    @BeforeEach
    void seed() {
        sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Admin Controller Test Nursery " + UUID.randomUUID());
        ownerSubject = "sub-owner-" + UUID.randomUUID();
        jdbc.update("INSERT INTO seller_users (seller_id, subject_id, role) VALUES (?, ?, 'OWNER')", sellerId, ownerSubject);
    }

    private void fundSellerPayable(String amount) {
        UUID buyerId = UUID.randomUUID();
        ledger.post("admin-controller-fund:" + UUID.randomUUID(), "TEST_FUNDING", "test",
            List.of(
                new LedgerService.EntrySpec(accounts.buyerReceivable(buyerId), Money.of(amount).negate()),
                new LedgerService.EntrySpec(accounts.sellerPayable(sellerId), Money.of(amount))));
    }

    private UUID insertCycle(String netAmount, String status) {
        return jdbc.queryForObject("""
            INSERT INTO payout_cycles (seller_id, period_start, period_end, status, gross_amount, withheld_amount, net_amount, idempotency_key)
            VALUES (?, ?, ?, ?, ?, 0, ?, ?) RETURNING id
            """, UUID.class, sellerId, periodStart, periodEnd, status,
            new BigDecimal(netAmount), new BigDecimal(netAmount), "payout:" + UUID.randomUUID());
    }

    @Test
    void sellerOwner_cannotApproveTheirOwnPayout() throws Exception {
        UUID cycleId = insertCycle("500.00", "PENDING_APPROVAL");

        mockMvc.perform(post("/v1/admin/payouts/" + cycleId + "/approve")
                .with(jwt().jwt(j -> j.subject(ownerSubject))))
            .andExpect(status().isForbidden());
    }

    @Test
    void sellerOwner_cannotInitiateTheirOwnPayout() throws Exception {
        fundSellerPayable("500.00");
        UUID cycleId = insertCycle("500.00", "APPROVED");

        mockMvc.perform(post("/v1/admin/payouts/" + cycleId + "/initiate")
                .with(jwt().jwt(j -> j.subject(ownerSubject))))
            .andExpect(status().isForbidden());
    }

    @Test
    void platformAdmin_canApproveThenInitiate() throws Exception {
        fundSellerPayable("500.00");
        UUID cycleId = insertCycle("500.00", "PENDING_APPROVAL");

        mockMvc.perform(post("/v1/admin/payouts/" + cycleId + "/approve")
                .with(jwt().jwt(j -> j.subject("sub-ops-" + UUID.randomUUID()))
                    .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(post("/v1/admin/payouts/" + cycleId + "/initiate")
                .with(jwt().jwt(j -> j.subject("sub-ops-" + UUID.randomUUID()))
                    .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.providerRef").exists());
    }

    @Test
    void initiatingBeyondPayableBalance_returns409() throws Exception {
        fundSellerPayable("100.00");
        UUID cycleId = insertCycle("5000.00", "APPROVED");

        mockMvc.perform(post("/v1/admin/payouts/" + cycleId + "/initiate")
                .with(jwt().jwt(j -> j.subject("sub-ops-" + UUID.randomUUID()))
                    .authorities(new SimpleGrantedAuthority("PLATFORM_ADMIN"))))
            .andExpect(status().isConflict());
    }
}
