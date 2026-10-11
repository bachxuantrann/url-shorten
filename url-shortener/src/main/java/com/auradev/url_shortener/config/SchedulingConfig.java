package com.auradev.url_shortener.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật {@code @Scheduled} cho các job nền (ví dụ {@code UrlExpirationJob}).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
