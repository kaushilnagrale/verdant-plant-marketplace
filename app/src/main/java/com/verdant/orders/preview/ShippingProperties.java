package com.verdant.orders.preview;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "verdant.shipping")
public record ShippingProperties(BigDecimal perShipmentFee, BigDecimal platformCap) { }
