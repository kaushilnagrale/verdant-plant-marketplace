package com.verdant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
@EnableCaching
// Boot auto-configures a KafkaTemplate for the producer side on its own,
// but @KafkaListener annotations (ListingProjector, Week 9 - the first
// consumer this project has ever needed) are only discovered/registered
// with this present.
@EnableKafka
public class VerdantApplication {
    public static void main(String[] args) {
        SpringApplication.run(VerdantApplication.class, args);
    }
}
