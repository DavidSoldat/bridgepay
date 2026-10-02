package com.bridgepay.creditrisk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis-backed score cache (spec section 9 - "Redis-backed @Cacheable on the
 * score lookup"). A short TTL because a checkout's requestedAt/hour-of-day
 * feature is part of the score, so a stale cache entry isn't safe to reuse
 * for long - the cache exists to absorb bursts/retries of the same checkout,
 * not to serve minutes-old decisions. The credit-limit cache shares the 30 s
 * TTL so a completed plan raises the limit within half a minute.
 */
@Configuration
public class CacheConfig {

    public static final String CREDIT_SCORE_CACHE = "credit-score";
    public static final String CREDIT_LIMIT_CACHE = "credit-limit";

    @Bean
    RedisCacheManagerBuilderCustomizer creditScoreCacheCustomizer() {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(30))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()));
        return builder -> builder
                .withCacheConfiguration(CREDIT_SCORE_CACHE, config)
                .withCacheConfiguration(CREDIT_LIMIT_CACHE, config);
    }
}
