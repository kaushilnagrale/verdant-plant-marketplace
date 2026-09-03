package com.verdant.logistics.weather;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "verdant.weather")
public record WeatherProperties(String apiKey) { }
