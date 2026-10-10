package com.auradev.url_shortener.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Hạ tầng thật (Postgres, Redis, Kafka) chạy bằng Testcontainers cho integration test.
 *
 * <p>Dùng {@code @Import(TestcontainersConfiguration.class)} trên test class.
 * {@link ServiceConnection} tự ghi đè datasource / redis / kafka của Spring bằng
 * cổng ngẫu nhiên của container nên không đụng tới DB dev đang chạy.
 * Các bean là singleton trong context, Spring cache context giữa các test nên
 * container chỉ khởi động một lần cho cả test suite (cùng cấu hình context).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }

    @Bean
    ConfluentKafkaContainer kafkaContainer() {
        return new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
    }

    /** Kafka không có ConnectionDetails tự động cho container này nên trỏ bootstrap-servers thủ công. */
    @Bean
    DynamicPropertyRegistrar kafkaProperties(ConfluentKafkaContainer kafka) {
        return registry -> registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
}
