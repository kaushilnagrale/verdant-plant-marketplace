package com.verdant.search.admin;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * PLATFORM_ADMIN only, same manual-authorization style as
 * PayoutAdminController (not @PreAuthorize) - falls under SecurityConfig's
 * default .anyRequest().authenticated() (not in the permitAll list), so a
 * valid JWT is required just to reach here, and this class's own check
 * narrows it to platform ops specifically.
 *
 * 202 Accepted, not 200: backfillAllAsync() is fire-and-forget (@Async,
 * BackfillService) - a full catalog backfill can genuinely take a while,
 * and the real workflow (scripts/reindex-all.sh) is to poll document
 * counts against the new index until they match, then flip the alias -
 * not to wait on this call's own response.
 */
@RestController
@RequestMapping("/v1/admin/search")
public class BackfillController {

    private final BackfillService backfillService;

    public BackfillController(BackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @PostMapping("/backfill")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void backfill(Authentication authentication, @RequestBody BackfillRequest request) {
        requirePlatformAdmin(authentication);
        backfillService.backfillAllAsync(request.targetIndex());
    }

    private void requirePlatformAdmin(Authentication authentication) {
        boolean isPlatformAdmin = authentication.getAuthorities().stream()
            .anyMatch(a -> "PLATFORM_ADMIN".equals(a.getAuthority()));
        if (!isPlatformAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform ops only");
        }
    }
}
