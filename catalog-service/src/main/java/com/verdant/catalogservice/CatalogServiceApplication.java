package com.verdant.catalogservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A real, separate process (its own main(), its own bootJar) connecting
 * to the SAME Postgres instance as the monolith, but as
 * catalog_service_role (V39__service_isolation.sql) rather than the
 * monolith's own broad application role - the actual mechanism proven
 * for real (ServiceIsolationTest, :app) is exercised here from the
 * consuming side: this whole application literally cannot start
 * successfully, let alone serve a request, against any table outside
 * what that role was granted.
 */
@SpringBootApplication
public class CatalogServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
