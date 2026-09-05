package com.verdant.common.tenancy;

import com.verdant.identity.SellerUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Narrows TenantContext to exactly one seller for the life of a request,
 * when (and only when) the request is authenticated as a real seller user
 * - resolved the same way SellerOrderController already did it manually
 * (JWT subject -> seller_users), just done once per request instead of
 * once per controller. Every other kind of request (unauthenticated,
 * buyer-facing/permitAll, platform ops) is deliberately left at
 * TenantContext's own default (full access) - see its doc comment for why
 * that default exists at all.
 *
 * Must run AFTER Spring Security has resolved the JWT into an
 * Authentication - wired into the filter chain explicitly via
 * addFilterAfter(..., BearerTokenAuthenticationFilter.class) in
 * SecurityConfig, not left as a plain @Component, because a
 * component-scanned Filter's position relative to Spring Security's own
 * filter chain is not guaranteed and getting this ordering wrong would
 * mean SecurityContextHolder is still empty when this runs.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    private final SellerUserRepository sellerUsers;

    public TenantContextFilter(SellerUserRepository sellerUsers) {
        this.sellerUsers = sellerUsers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                sellerUsers.findBySubjectId(jwt.getSubject())
                    .ifPresent(su -> TenantContext.setSeller(su.getSellerId()));
            }
            chain.doFilter(request, response);
        } finally {
            // Always clear, even on the permitAll/no-JWT path - a thread
            // from the servlet container's pool WILL be reused for later,
            // completely unrelated requests, and TenantContext is a
            // ThreadLocal.
            TenantContext.clear();
        }
    }
}
