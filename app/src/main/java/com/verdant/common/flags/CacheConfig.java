package com.verdant.common.flags;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Real Redis-backed cache, not in-process - the whole point of the "flags"
 * cache is that every app instance sees the same flag state within the
 * same short window, not each instance flipping on its own clock (which an
 * in-process cache like Caffeine would give you at more than one replica).
 *
 * JacksonJsonRedisSerializer<Flag> (not the "Generic" variant) is Spring
 * Data Redis's Jackson-3 serializer typed to exactly one class - matches
 * the tools.jackson.* package this project already uses for JSONB (see
 * OutboxWriter, Day 31). The Generic variant relies on an embedded "@class"
 * field for polymorphic deserialization and, tried first here, came back
 * as a raw LinkedHashMap on read instead of a real Flag (ClassCastException
 * caught by FeatureFlagsTest, not assumed) - typing the serializer to the
 * one record this cache ever holds sidesteps that entirely, and avoids the
 * polymorphic-deserialization attack surface "@class" implies for free.
 * JSON over the wire also means a value written by one app version can
 * still be read by another during a rolling deploy - Java's default JDK
 * serialization (what Boot falls back to with zero config) breaks the
 * moment the class shape changes even slightly, a bad trait for anything
 * crossing a process boundary in production.
 */
@Configuration
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration flagsConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofSeconds(30))
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new JacksonJsonRedisSerializer<>(FeatureFlags.Flag.class)));

        return RedisCacheManager.builder(connectionFactory)
            .withCacheConfiguration("flags", flagsConfig)
            .build();
    }
}
