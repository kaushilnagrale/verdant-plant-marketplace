package com.verdant.identity.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A dedicated, narrowly-scoped confidential client with only the
 * realm-management "manage-users" service-account role - not the
 * bootstrap master-realm admin this project's own setup-keycloak.sh
 * otherwise uses for one-time local setup. Real production apps that
 * build a custom registration form on top of Keycloak use exactly this
 * pattern (a service-account client, never the platform's own admin
 * superuser credentials embedded in a running application).
 */
@ConfigurationProperties(prefix = "keycloak.admin")
public record KeycloakAdminProperties(String baseUrl, String realm, String clientId, String clientSecret) { }
