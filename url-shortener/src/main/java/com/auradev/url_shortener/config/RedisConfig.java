package com.auradev.url_shortener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Cấu hình Redis.
 *
 * <p>Định nghĩa hai bean {@link RedisTemplate}:
 * <ul>
 *   <li>{@code redisTemplate} – key/value đều là String (dùng cho JWT token cache)</li>
 *   <li>{@code jsonRedisTemplate} – key String, value JSON object (dùng cho cache user)</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * RedisTemplate dùng cho JWT: lưu refresh token và blacklist access token.
     * Cả key và value đều dùng {@link StringRedisSerializer} để dễ debug bằng CLI.
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * RedisTemplate dùng cho cache object (ví dụ: UserResponse).
     * Key là String, value được serialize thành JSON.
     */
    @Bean
    public RedisTemplate<String, Object> jsonRedisTemplate(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper
    ) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
