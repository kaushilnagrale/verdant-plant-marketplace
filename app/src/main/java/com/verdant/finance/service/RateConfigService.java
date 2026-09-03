package com.verdant.finance.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Rates are looked up by the date of the TRANSACTION, not today's date, so a
 * rate change never retroactively restates old settlements. A seller-scoped
 * rate (scopeRef non-null) takes precedence over the platform default for
 * that same rate type, if one is currently effective.
 */
@Service
public class RateConfigService {

    private final JdbcTemplate jdbc;

    public RateConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal rate(String rateType, UUID scopeRef, LocalDate asOf) {
        if (scopeRef != null) {
            List<BigDecimal> scoped = effectiveRates(rateType, scopeRef, asOf);
            if (!scoped.isEmpty()) {
                return scoped.get(0);
            }
        }

        List<BigDecimal> platformDefault = effectiveRates(rateType, null, asOf);
        if (platformDefault.isEmpty()) {
            throw new IllegalStateException(
                "No effective %s rate as of %s".formatted(rateType, asOf));
        }
        return platformDefault.get(0);
    }

    private List<BigDecimal> effectiveRates(String rateType, UUID scopeRef, LocalDate asOf) {
        String scopeClause = scopeRef == null ? "scope_ref IS NULL" : "scope_ref = ?";
        String sql = """
            SELECT rate FROM rate_config
             WHERE rate_type = ? AND %s
               AND effective_from <= ?
               AND (effective_to IS NULL OR effective_to > ?)
             ORDER BY effective_from DESC LIMIT 1
            """.formatted(scopeClause);

        return scopeRef == null
            ? jdbc.queryForList(sql, BigDecimal.class, rateType, asOf, asOf)
            : jdbc.queryForList(sql, BigDecimal.class, rateType, scopeRef, asOf, asOf);
    }
}
