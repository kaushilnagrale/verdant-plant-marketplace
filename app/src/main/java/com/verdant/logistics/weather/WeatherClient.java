package com.verdant.logistics.weather;

import java.time.LocalDate;
import java.util.List;

/** Only the network-calling part of talking to a weather provider goes behind this interface. */
public interface WeatherClient {

    List<DailyForecast> sevenDayForecast(Coordinates coords);

    record Coordinates(double latitude, double longitude) { }

    record DailyForecast(LocalDate date, double maxTempC, double minTempC, double rainfallMm, String alertType) { }
}
