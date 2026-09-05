package com.verdant.forecasting.web;

import com.verdant.forecasting.domain.Forecast;
import com.verdant.forecasting.service.DemandForecaster;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.UUID;

/**
 * PLATFORM_ADMIN only, same manual-authorization style as
 * BackfillController/PayoutAdminController (not @PreAuthorize) - falls
 * under SecurityConfig's default .anyRequest().authenticated(). No
 * seller-facing demand-planning UI exists yet (that's real, separate,
 * future scope - it would need per-seller product-ownership checks this
 * internal ops tool doesn't); this is deliberately the smaller, real
 * thing: platform ops asking "how much of X should we expect around
 * date Y" for any product, not a seller self-service view.
 */
@RestController
@RequestMapping("/v1/admin/forecast")
public class ForecastController {

    private final DemandForecaster forecaster;

    public ForecastController(DemandForecaster forecaster) {
        this.forecaster = forecaster;
    }

    @GetMapping("/{canonicalProductId}")
    public Forecast forecast(Authentication authentication, @PathVariable UUID canonicalProductId,
                              @RequestParam LocalDate onDate) {
        requirePlatformAdmin(authentication);
        return forecaster.forecast(canonicalProductId, onDate);
    }

    private void requirePlatformAdmin(Authentication authentication) {
        boolean isPlatformAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> "PLATFORM_ADMIN".equals(a.getAuthority()));
        if (!isPlatformAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform ops only");
        }
    }
}
