package com.verdant.logistics.weather;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** DispatchGateTest/HoldLifecycleTest seed weather_forecasts directly (real integration-test data, not a live API) - this only exists so ForecastRefreshJob has a bean to wire under the test profile. */
@Component
@Profile("test")
public class StubWeatherClient implements WeatherClient {

    @Override
    public List<DailyForecast> sevenDayForecast(Coordinates coords) {
        return List.of(new DailyForecast(LocalDate.now(), 28.0, 20.0, 0.0, null));
    }
}
