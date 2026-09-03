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
