package com.verdant.logistics.weather;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real OpenWeather integration. LIVE-VERIFIED (2026-09-03): the real key
 * initially came back "Invalid API key" (normal for a brand-new key -
 * their own docs note an activation delay), then once active came back
 * rejecting the One Call 3.0 endpoint this class originally targeted -
 * "requires a separate subscription to the One Call by Call plan", a real
 * OpenWeather gotcha not obvious from their docs (a free API key alone
 * isn't enough for that specific endpoint, even at $0 cost). Switched to
 * /data/2.5/forecast (5 days, 3-hour steps), which the same key called
 * successfully with real data on the first try.
 *
 * Known, honest limitation this switch introduces: this endpoint only
 * covers 5 days, not 7 - a shipment with a transit window longer than 5
 * days (the catalog's own transit_estimates go up to 9, REMOTE-REMOTE)
 * simply has no forecast data for the tail of its window.
 * WeatherForecastRepository.forWindow() aggregates whatever rows DO exist
 * rather than failing outright, so the gate still evaluates on the days it
 * actually knows about - a real gap worth revisiting with a paid One Call
 * subscription if this ever needed real production coverage, not silently
 * pretended away here.
 */
@Component
@Profile("!test")
public class OpenWeatherClient implements WeatherClient {

    private static final String BASE_URL = "https://api.openweathermap.org/data/2.5/forecast";

    private final WeatherProperties props;
    private final RestClient http;

    public OpenWeatherClient(WeatherProperties props) {
        this.props = props;
        this.http = RestClient.create();
    }

    @Override
    public List<DailyForecast> sevenDayForecast(Coordinates coords) {
        String url = BASE_URL + "?lat=" + coords.latitude() + "&lon=" + coords.longitude()
            + "&appid=" + props.apiKey() + "&units=metric";

        String raw = http.get().uri(url).retrieve().body(String.class);
        JSONArray list = new JSONObject(raw).getJSONArray("list");

        // Aggregate 3-hourly entries into one row per calendar date (UTC) -
        // the API gives ~8 entries/day, this project's model is one row per
        // (pincode, forecast_date).
        Map<LocalDate, double[]> byDate = new LinkedHashMap<>(); // [maxTemp, minTemp, rainfallMm]
        for (int i = 0; i < list.length(); i++) {
            JSONObject entry = list.getJSONObject(i);
            LocalDate date = Instant.ofEpochSecond(entry.getLong("dt")).atZone(ZoneOffset.UTC).toLocalDate();
            JSONObject main = entry.getJSONObject("main");
            double tempMax = main.getDouble("temp_max");
            double tempMin = main.getDouble("temp_min");
            double rain3h = entry.has("rain") ? entry.getJSONObject("rain").optDouble("3h", 0.0) : 0.0;

            double[] agg = byDate.computeIfAbsent(date, d -> new double[]{-999, 999, 0});
            agg[0] = Math.max(agg[0], tempMax);
            agg[1] = Math.min(agg[1], tempMin);
            agg[2] += rain3h;
        }

        List<DailyForecast> forecasts = new ArrayList<>();
        byDate.forEach((date, agg) -> forecasts.add(new DailyForecast(date, agg[0], agg[1], agg[2], null)));
        return forecasts;
    }
}
