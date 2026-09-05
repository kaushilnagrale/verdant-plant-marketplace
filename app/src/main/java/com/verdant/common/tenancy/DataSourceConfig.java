package com.verdant.common.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Flyway needs the RAW, superuser-capable connection - migrations
 * genuinely need elevated privileges (V2 does CREATE ROLE, V1 does CREATE
 * EXTENSION), which the restricted verdant_app role must never have.
 *
 * Every other bean (JdbcTemplate, JPA/Hibernate) gets the wrapped,
 * tenant-aware one instead, via @Primary - see TenantAwareDataSource.
 */
@Configuration
public class DataSourceConfig {

    /**
     * REAL BUG, found by Day 46's first new migration since this class was
     * introduced (V31): naming this bean method "flywayDataSource" is NOT
     * enough on its own. FlywayAutoConfiguration's real injection point in
     * Boot 4.1.1 is an ObjectProvider<DataSource> qualified with the
     * @FlywayDataSource annotation (confirmed by decompiling
     * spring-boot-flyway - it carries @Qualifier but no value(), so
     * Spring's by-name qualifier fallback can never match a plain bean
     * name either). Without the annotation below, that qualified lookup
     * silently found nothing and Flyway fell back to the @Primary
     * (wrapped, RLS-enforcing) DataSource instead - meaning every
     * migration applied since TenantAwareDataSource was introduced would
     * run as verdant_app, not the superuser verdant. V1-V30 never
     * surfaced this: they were already applied to every reused
     * Testcontainers/dev database from long before this class existed, so
     * Flyway had nothing new to migrate and never actually exercised this
     * path. V31 was the first real migration attempt since, and failed
     * with "permission denied for schema public" - exactly what
     * verdant_app CREATE-ing a table would produce, since ALTER DEFAULT
     * PRIVILEGES (V6) grants privileges on objects once they exist, not
     * schema-level CREATE itself. Would have broken the very next real
     * migration in production identically.
     *
     * DataSourceProperties itself is NOT declared as our own @Bean - Boot
     * already auto-configures exactly one from spring.datasource.*
     * regardless of whether a DataSource bean exists - declaring a second
     * one here created a real, immediately-obvious ambiguous-bean error
     * the first time this was booted, not a hypothetical one. Just
     * consume Boot's own bean directly.
     */
    @FlywayDataSource
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
