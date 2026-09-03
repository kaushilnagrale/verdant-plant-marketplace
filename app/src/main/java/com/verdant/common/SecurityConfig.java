package com.verdant.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/v1/cart/**", "/v1/checkout/**", "/v1/webhooks/**", "/v1/catalog/**").permitAll()
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
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
            // Stateless bearer-token API - no cookies/sessions carry auth, so
            // there is nothing for a cross-site request to forge. CSRF
            // protection exists specifically for cookie-based auth and is
            // meaningless (and only in the way) here.
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
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
