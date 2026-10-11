package com.auradev.url_shortener.config;

import io.lettuce.core.tracing.MicrometerTracing;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Cấu hình trace/log/metric cho OpenTelemetry (xuất qua OTLP tới OpenObserve, xem {@code application-*.yaml}).
 *
 * <ul>
 *   <li>Trace HTTP, Kafka (producer + consumer, cùng một trace nhờ header) và JDBC có sẵn từ Boot và
 *       {@code datasource-micrometer}; ở đây bổ sung Redis (Lettuce).</li>
 *   <li>Request tới {@code /actuator/**} (health check của Docker/nginx) không tạo span.</li>
 *   <li>Gắn appender Logback với bean {@link OpenTelemetry} để log đi theo OTLP.</li>
 * </ul>
 */
@Configuration
public class ObservabilityConfig {

    /** Health check gọi liên tục sẽ làm loãng trace và tốn băng thông; bỏ qua. */
    @Bean
    ObservationPredicate skipActuatorObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier() != null
                && server.getCarrier().getRequestURI().startsWith("/actuator"));
    }

    /** Mỗi lệnh Redis là một span con (cache redirect, rate limit, token). */
    @Bean
    ClientResourcesBuilderCustomizer lettuceTracingCustomizer(ObservationRegistry observationRegistry) {
        return builder -> builder.tracing(new MicrometerTracing(observationRegistry, "redis"));
    }

    /**
     * Gắn {@link OpenTelemetryAppender} (khai báo trong {@code logback-spring.xml}) với SDK sau khi bean
     * {@link OpenTelemetry} sẵn sàng; log phát sinh trước đó được appender giữ lại rồi gửi bù.
     */
    @Bean
    InitializingBean openTelemetryLogbackInstaller(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }
}
