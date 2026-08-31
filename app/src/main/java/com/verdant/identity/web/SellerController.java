package com.verdant.identity.web;

import com.verdant.identity.domain.IllegalStateTransitionException;
import com.verdant.identity.domain.Seller;
import com.verdant.identity.domain.SellerCapabilities;
import com.verdant.identity.domain.SellerStatus;
import com.verdant.identity.service.SellerNotFoundException;
import com.verdant.identity.service.SellerOnboardingService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/sellers")
public class SellerController {

    private final SellerOnboardingService service;

    public SellerController(SellerOnboardingService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Seller create(@RequestBody CreateSellerRequest request) {
        return service.createDraft(request.businessName());
    }

    @PostMapping("/{id}/submit")
    public Seller submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.transition(id, SellerStatus.PENDING_REVIEW, jwt.getSubject(), null);
    }

    @PostMapping("/{id}/approve")
    public Seller approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.transition(id, SellerStatus.APPROVED, jwt.getSubject(), "approved via API");
    }

    @GetMapping("/{id}/capabilities")
    public SellerCapabilities capabilities(@PathVariable UUID id) {
        return service.capabilities(id);
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void handleIllegalTransition() { }

    @ExceptionHandler(SellerNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleNotFound() { }
}
