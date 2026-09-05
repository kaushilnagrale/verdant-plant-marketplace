package com.verdant.seller;

import com.verdant.identity.SellerUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Authenticated (seller JWT) - relies on SecurityConfig's default anyRequest().authenticated(), no explicit permitAll needed. */
@RestController
@RequestMapping("/v1/seller/orders")
public class SellerOrderController {

    private final SellerOrderService orders;
    private final SellerUserRepository sellerUsers;

    public SellerOrderController(SellerOrderService orders, SellerUserRepository sellerUsers) {
        this.orders = orders;
        this.sellerUsers = sellerUsers;
    }

    @GetMapping
    public List<SellerOrderSummary> myOrders(@AuthenticationPrincipal Jwt jwt) {
        return orders.myOrders(currentSellerId(jwt));
    }

    /**
     * The seller app's own real "who am I" call - it knows its own JWT
     * subject but not the internal seller id SellerAccountController's
     * endpoints are keyed on, until this resolves it once at sign-in.
     * Lives here (GET /v1/seller/orders/me), not a nicer /v1/seller/me,
     * only because this controller already owns currentSellerId() and
     * /v1/seller's own base path isn't mapped to any controller at all -
     * a real, minor naming compromise, not a design statement.
     */
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return new MeResponse(currentSellerId(jwt));
    }

    public record MeResponse(java.util.UUID sellerId) { }

    @PostMapping("/{orderId}/pack")
    public void pack(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        orders.markPacked(currentSellerId(jwt), orderId);
    }

    @PostMapping("/{orderId}/dispatch")
    public void dispatch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        orders.markDispatched(currentSellerId(jwt), orderId);
    }

    private UUID currentSellerId(Jwt jwt) {
        return sellerUsers.findBySubjectId(jwt.getSubject())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No seller mapped to this account"))
            .getSellerId();
    }
}
