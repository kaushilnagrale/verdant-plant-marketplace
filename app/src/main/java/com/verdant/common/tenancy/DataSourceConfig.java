package com.verdant.common.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Flyway needs the RAW, superuser-capable connection - migrations
 * genuinely need elevated privileges (V2 does CREATE ROLE, V1 does CREATE
 * EXTENSION), which the restricted verdant_app role must never have.
 * Named exactly "flywayDataSource" because Spring Boot's own
 * FlywayAutoConfiguration looks for a bean by that exact name before
 * falling back to the primary DataSource - this is the standard,
 * documented way to give Flyway a different connection than the rest of
 * the app, not a workaround.
 *
 * Every other bean (JdbcTemplate, JPA/Hibernate) gets the wrapped,
 * tenant-aware one instead, via @Primary - see TenantAwareDataSource.
 */
@Configuration
public class DataSourceConfig {

    /**
     * Not declared as our own @Bean - Boot already auto-configures exactly
     * one DataSourceProperties bean from spring.datasource.* regardless of
     * whether a DataSource bean exists (it needs one for its own purposes,
     * e.g. Flyway's fallback resolution) - declaring a second one here
     * created a real, immediately-obvious ambiguous-bean error the first
     * time this was booted, not a hypothetical one. Just consume Boot's
     * own bean directly.
     */
    @Bean
    public DataSource flywayDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Primary
    @Bean
    public DataSource dataSource(@Qualifier("flywayDataSource") DataSource raw) {
        return new TenantAwareDataSource(raw);
    }
}
