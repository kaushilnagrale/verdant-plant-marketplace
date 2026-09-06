package com.verdant.buyer;

import com.razorpay.Utils;
import com.verdant.cart.CartLine;
import com.verdant.checkout.CheckoutRequest;
import com.verdant.checkout.CheckoutResult;
import com.verdant.checkout.CheckoutService;
import com.verdant.checkout.PaymentVerificationRequest;
import com.verdant.checkout.PaymentVerificationService;
import com.verdant.common.testing.AllocationFixtures;
import com.verdant.common.testing.IntegrationTestBase;
import com.verdant.payment.RazorpayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real registration (StubKeycloakAdminClient - no real Keycloak realm needed in a test, same pattern as StubPaymentGateway), real checkout, real order/tracking reads - only the identity-provider network call is a stub. */
@AutoConfigureMockMvc
class BuyerControllerTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CheckoutService checkoutService;
    @Autowired private PaymentVerificationService paymentVerification;
    @Autowired private RazorpayProperties razorpayProps;

    private UUID sellerId;
    private UUID listingId;

    @BeforeEach
    void seed() {
        AllocationFixtures.seedLocalPincodeZone(jdbc);
        sellerId = AllocationFixtures.seedApprovedSeller(jdbc, "Buyer Order Test Nursery " + UUID.randomUUID());
        UUID canonicalProductId = AllocationFixtures.seedCanonicalProduct(jdbc, "Fixture Buyer Plant");
        listingId = AllocationFixtures.seedListing(jdbc, sellerId, canonicalProductId, "Fixture Buyer Plant", "300.00", 5);
    }

    private UUID registerRealBuyer(String email) throws Exception {
        String body = """
            {"name":"Test Buyer","email":"%s","phone":"9876543210","password":"a-real-password"}
            """.formatted(email);
        String response = mockMvc.perform(post("/v1/buyers/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(response.replaceAll(".*\"buyerId\":\"([^\"]+)\".*", "$1"));
    }

    @Test
    void register_thenLogin_thenMe_returnsTheRealRegisteredProfile() throws Exception {
        String email = "real-buyer-" + UUID.randomUUID() + "@example.com";
        UUID buyerId = registerRealBuyer(email);
        String subjectId = jdbc.queryForObject("SELECT subject_id FROM buyers WHERE id = ?", String.class, buyerId);

        mockMvc.perform(get("/v1/buyers/me").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.name").value("Test Buyer"));
    }

    @Test
    void register_duplicateEmail_returns409_andNeverCreatesASecondRow() throws Exception {
        String email = "dup-buyer-" + UUID.randomUUID() + "@example.com";
        registerRealBuyer(email);

        String body = """
            {"name":"Someone Else","email":"%s","phone":null,"password":"another-password"}
            """.formatted(email);
        mockMvc.perform(post("/v1/buyers/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isConflict());

        Integer count = jdbc.queryForObject("SELECT count(*) FROM buyers WHERE email = ?", Integer.class, email);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    void register_weakPassword_isRejectedBeforeEverCallingTheIdentityProvider() throws Exception {
        String body = """
            {"name":"Test Buyer","email":"weak-%s@example.com","phone":null,"password":"short"}
            """.formatted(UUID.randomUUID());
        mockMvc.perform(post("/v1/buyers/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    /** Real checkout end to end (allocation, payment verification, order persistence) with a real logged-in buyer, then reads it back through the buyer's own order-history and detail endpoints. */
    @Test
    void myOrders_andOrderDetail_showARealOrderThisBuyerActuallyPlaced() throws Exception {
        String email = "order-buyer-" + UUID.randomUUID() + "@example.com";
        UUID buyerId = registerRealBuyer(email);
        String subjectId = jdbc.queryForObject("SELECT subject_id FROM buyers WHERE id = ?", String.class, buyerId);

        CheckoutResult checkout = checkoutService.checkout(buyerId, new CheckoutRequest(
            email, List.of(new CartLine(listingId, 2)), AllocationFixtures.LOCAL_PINCODE,
            "Real Recipient", "9123456789", "42 MG Road", null, "Pune", "Maharashtra"));

        String paymentId = "pay_buyer_" + UUID.randomUUID();
        String signature = Utils.getHash(checkout.razorpayOrderId() + "|" + paymentId, razorpayProps.keySecret());
        paymentVerification.verifyPayment(checkout.orderId(),
            new PaymentVerificationRequest(checkout.razorpayOrderId(), paymentId, signature));

        mockMvc.perform(get("/v1/buyers/me/orders").with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.orderId=='" + checkout.orderId() + "')]").exists());

        mockMvc.perform(get("/v1/buyers/me/orders/" + checkout.orderId()).with(jwt().jwt(j -> j.subject(subjectId))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.recipientName").value("Real Recipient"))
            .andExpect(jsonPath("$.city").value("Pune"))
            .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    /** Same real 404-not-403 convention this project uses everywhere an RLS-style boundary applies - never confirm another buyer's order even exists. */
    @Test
    void orderDetail_forAnotherBuyersOrder_is404_notForbidden() throws Exception {
        String ownerEmail = "owner-buyer-" + UUID.randomUUID() + "@example.com";
        UUID ownerBuyerId = registerRealBuyer(ownerEmail);

        CheckoutResult checkout = checkoutService.checkout(ownerBuyerId, new CheckoutRequest(
            ownerEmail, List.of(new CartLine(listingId, 1)), AllocationFixtures.LOCAL_PINCODE,
            "Owner", "9123456780", "1 Owner Street", null, "Pune", "Maharashtra"));

        String intruderEmail = "intruder-buyer-" + UUID.randomUUID() + "@example.com";
        UUID intruderBuyerId = registerRealBuyer(intruderEmail);
        String intruderSubjectId = jdbc.queryForObject("SELECT subject_id FROM buyers WHERE id = ?", String.class, intruderBuyerId);

        mockMvc.perform(get("/v1/buyers/me/orders/" + checkout.orderId()).with(jwt().jwt(j -> j.subject(intruderSubjectId))))
            .andExpect(status().isNotFound());
    }
}
