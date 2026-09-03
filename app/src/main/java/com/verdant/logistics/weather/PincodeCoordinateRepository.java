package com.verdant.logistics.weather;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class PincodeCoordinateRepository {

    private final JdbcTemplate jdbc;

    public PincodeCoordinateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<WeatherClient.Coordinates> forPincode(String pincode) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
            "SELECT latitude, longitude FROM pincode_coordinates WHERE pincode = ?", pincode);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        var row = rows.get(0);
        return Optional.of(new WeatherClient.Coordinates(
            ((BigDecimal) row.get("latitude")).doubleValue(), ((BigDecimal) row.get("longitude")).doubleValue()));
    }
}
