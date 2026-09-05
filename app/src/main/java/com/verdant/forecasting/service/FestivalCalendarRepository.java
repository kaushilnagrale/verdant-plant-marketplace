package com.verdant.forecasting.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class FestivalCalendarRepository {

    private final JdbcTemplate jdbc;

    public FestivalCalendarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Festival(String name, LocalDate observedDate, int demandLeadDays) { }

    /** The nearest festival whose lead window covers targetDate, if any - targetDate itself still counts (someone ordering the morning of the festival for same-day pickup), the observed date does not need to be in the future. */
    public Optional<Festival> festivalCovering(LocalDate targetDate) {
        List<Festival> matches = jdbc.query("""
            SELECT name, observed_date, demand_lead_days
              FROM festival_calendar
             WHERE ? BETWEEN (observed_date - make_interval(days => demand_lead_days)) AND observed_date
             ORDER BY observed_date
             LIMIT 1
            """,
            (rs, rowNum) -> new Festival(rs.getString("name"),
                rs.getObject("observed_date", LocalDate.class), rs.getInt("demand_lead_days")),
            targetDate);
        return matches.stream().findFirst();
    }
}
