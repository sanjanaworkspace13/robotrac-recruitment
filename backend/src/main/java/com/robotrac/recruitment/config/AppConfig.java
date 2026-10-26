package com.robotrac.recruitment.config;

import com.robotrac.recruitment.core.FixedWindowRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

/** Shared infrastructure beans. The Clock bean lets tests move time forward to test deadlines. */
@Configuration
@EnableScheduling
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    public FixedWindowRateLimiter rateLimiter(Clock clock) {
        return new FixedWindowRateLimiter(clock);
    }

    /** Housekeeping for the in-memory rate limiter. */
    @Bean
    public RateLimiterJanitor rateLimiterJanitor(FixedWindowRateLimiter limiter) {
        return new RateLimiterJanitor(limiter);
    }

    public static class RateLimiterJanitor {
        private final FixedWindowRateLimiter limiter;

        public RateLimiterJanitor(FixedWindowRateLimiter limiter) {
            this.limiter = limiter;
        }

        @Scheduled(fixedDelay = 600_000)
        public void evict() {
            limiter.evictOlderThan(Duration.ofHours(1));
        }
    }
}
