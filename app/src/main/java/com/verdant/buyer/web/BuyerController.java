package com.verdant.buyer.web;

import com.verdant.buyer.domain.Buyer;
import com.verdant.buyer.domain.BuyerOrderDetail;
import com.verdant.buyer.domain.BuyerOrderSummary;
import com.verdant.buyer.service.BuyerOrderService;
import com.verdant.buyer.service.BuyerRegistrationService;
import com.verdant.buyer.service.BuyerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * /register is permitAll (SecurityConfig); everything under /me relies
 * on the default anyRequest().authenticated() and resolves the real
 * buyer id from the JWT subject, same convention as SellerOrderController.
 */
@RestController
@RequestMapping("/v1/buyers")
public class BuyerController {

    private final BuyerRegistrationService registration;
    private final BuyerRepository buyers;
    private final BuyerOrderService orders;

    public BuyerController(BuyerRegistrationService registration, BuyerRepository buyers, BuyerOrderService orders) {
        this.registration = registration;
        this.buyers = buyers;
        this.orders = orders;
    }

    public record RegisterRequest(String name, String email, String phone, String password) { }
    public record RegisterResponse(UUID buyerId) { }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@RequestBody RegisterRequest request) {
        UUID buyerId = registration.register(request.name(), request.email(), request.phone(), request.password());
        return new RegisterResponse(buyerId);
    }

    @GetMapping("/me")
    public Buyer me(@AuthenticationPrincipal Jwt jwt) {
        return currentBuyer(jwt);
    }

    public record UpdateProfileRequest(String name, String phone) { }

    /**
     * name/phone only, on purpose - email is the Keycloak username (the
     * real login identifier), and changing it is a real cross-system
     * operation (Keycloak's own user record, this row, and the unique
     * constraint on buyers.email) this endpoint doesn't attempt. Password
     * changes go through Keycloak entirely and have no representation
     * here at all - a real, separate, unbuilt "security settings"
     * concern, not silently folded into "profile info".
     */
    @PutMapping("/me")
    public Buyer updateProfile(@AuthenticationPrincipal Jwt jwt, @RequestBody UpdateProfileRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required");
        }
        Buyer current = currentBuyer(jwt);
        buyers.updateProfile(current.id(), request.name(), request.phone());
        return currentBuyer(jwt);
    }

    @GetMapping("/me/orders")
    public List<BuyerOrderSummary> myOrders(@AuthenticationPrincipal Jwt jwt) {
        return orders.myOrders(currentBuyer(jwt).id());
    }

    @GetMapping("/me/orders/{orderId}")
    public BuyerOrderDetail orderDetail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return orders.orderDetail(currentBuyer(jwt).id(), orderId);
    }

    private Buyer currentBuyer(Jwt jwt) {
        return buyers.findBySubjectId(jwt.getSubject())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No buyer account mapped to this login"));
    }
}
