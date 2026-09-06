package com.verdant.common;

import com.verdant.common.tenancy.TenantContextFilter;
import com.verdant.identity.SellerUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SellerUserRepository sellerUsers) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // Week 12 observability: REAL bug, found by Prometheus's
                // own scrape actually failing, not assumed - a scrape
                // config has no way to hold a JWT (it expires; there's
                // no refresh flow for a static scrape target), so
                // /actuator/prometheus needs the same permitAll
                // treatment /actuator/health already has, or Prometheus
                // gets 401 on every single scrape attempt. A real
                // production deployment would more properly put this on
                // a separate, network-restricted management port
                // (management.server.port) rather than share the public
                // API port at all - not done here, real, separate scope.
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/v1/cart/**", "/v1/webhooks/**", "/v1/catalog/**", "/v1/search/**", "/v1/assistant/**").permitAll()
                // Buyer accounts: registering one is necessarily
                // unauthenticated (there's no JWT yet); everything else
                // under /v1/buyers/** (profile, order history, tracking)
                // is NOT listed here on purpose - it falls through to the
                // default anyRequest().authenticated() below, same as
                // every seller-facing endpoint.
                .requestMatchers("/v1/buyers/register").permitAll()
                // Checkout now requires a real logged-in buyer (previously
                // permitAll, back when buyerEmail was just a free-text
                // field and there was no buyer identity to check against) -
                // CheckoutController resolves the real buyer id from the
                // JWT the same way SellerOrderController resolves a seller.
                // Not obvious, found by real end-to-end testing (Week 7) rather
                // than any prior test - every prior error-path test called the
                // service method directly or used MockMvc, neither of which
                // exercises Boot's actual error-dispatch machinery. A thrown
                // ResponseStatusException on ANY permitAll endpoint gets
                // internally redispatched to /error by Spring MVC's default
                // error handling; /error re-enters this SAME filter chain, and
                // without this line it isn't permitted - so every 4xx/5xx from
                // a permitAll endpoint was silently masked as 401 Unauthorized
                // (the OAuth2 resource server's entry point firing on the
                // error dispatch, not the original request). Standard,
                // well-known fix for this exact Spring Security + Boot
                // interaction.
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated())
            // Real, previously-invisible gap found while building Week 8's
            // payout statement access control: Keycloak has issued
            // SELLER_OWNER/SELLER_STAFF realm roles since seller onboarding
            // was first built, but nothing here ever mapped them into
            // Spring authorities - every single authorization decision this
            // whole project has made has been a manual, per-controller JWT-
            // subject lookup (SellerOrderController's own pattern), never a
            // real hasRole()/hasAuthority() check. This is the first one.
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(keycloakRealmRoleConverter())))
            // Stateless bearer-token API - no cookies/sessions carry auth, so
            // there is nothing for a cross-site request to forge. CSRF
            // protection exists specifically for cookie-based auth and is
            // meaningless (and only in the way) here.
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            // Runs after Spring Security's own JWT authentication filter,
            // deliberately - see TenantContextFilter's own doc comment for
            // why a plain @Component-registered Filter's ordering relative
            // to the security chain isn't reliable enough for this.
            .addFilterAfter(new TenantContextFilter(sellerUsers), BearerTokenAuthenticationFilter.class)
            // Separate from the permitAll rules above: those govern whether
            // a REQUEST is authorized once it arrives, CORS governs whether
            // a BROWSER on a different origin (the frontend on :5173, the
            // API on :8080 - Day 34) is even allowed to send it in the
            // first place. Without this, every request from the real
            // frontend would be silently blocked by the browser itself,
            // regardless of how permissive the rules above are.
            .cors(Customizer.withDefaults());
        return http.build();
    }

    /**
     * Keycloak nests realm roles under a "realm_access.roles" claim, not
     * the flat "scope"/"scp" claim Spring Security's own default
     * JwtGrantedAuthoritiesConverter looks at - this is the standard,
     * documented way to bridge the two. Roles are exposed as plain
     * authorities (e.g. "SELLER_OWNER"), not "ROLE_"-prefixed, matching
     * how they're actually checked (hasAuthority(), not hasRole()) in
     * PayoutStatementController.
     */
    private Converter<Jwt, AbstractAuthenticationToken> keycloakRealmRoleConverter() {
        return jwt -> {
            Collection<GrantedAuthority> authorities = realmRoles(jwt).stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
            return new JwtAuthenticationToken(jwt, authorities);
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
            return List.of();
        }
        return (List<String>) roles;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${verdant.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
