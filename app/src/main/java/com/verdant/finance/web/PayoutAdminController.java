package com.verdant.finance.web;

import com.verdant.finance.domain.PayoutCycle;
import com.verdant.finance.service.InsufficientPayableException;
import com.verdant.finance.service.PayoutService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Money moves only after a human platform operator explicitly approves
 * and initiates it (Day 47.1) - PLATFORM_ADMIN only, never a seller, not
 * even their own cycle. Same manual-authorization style as every other
 * controller in this project, not @PreAuthorize.
 */
@RestController
@RequestMapping("/v1/admin/payouts")
public class PayoutAdminController {

    private final PayoutService payoutService;

    public PayoutAdminController(PayoutService payoutService) {
        this.payoutService = payoutService;
    }

    @PostMapping("/{cycleId}/approve")
    public PayoutCycle approve(Authentication authentication, @PathVariable UUID cycleId) {
        requirePlatformAdmin(authentication);
        return payoutService.approve(cycleId, authentication.getName());
    }

    @PostMapping("/{cycleId}/initiate")
    public PayoutCycle initiate(Authentication authentication, @PathVariable UUID cycleId) {
        requirePlatformAdmin(authentication);
        return payoutService.initiate(cycleId, authentication.getName());
    }

    private void requirePlatformAdmin(Authentication authentication) {
        boolean isPlatformAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> "PLATFORM_ADMIN".equals(a.getAuthority()));
        if (!isPlatformAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform ops only");
        }
    }

    @ExceptionHandler(InsufficientPayableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleInsufficientPayable(InsufficientPayableException e) {
        return e.getMessage();
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleWrongCycleState(IllegalStateException e) {
        return e.getMessage();
    }
}
