package com.auradev.url_shortener.consumer;

import com.auradev.url_shortener.config.ClickProperties;
import com.auradev.url_shortener.event.ClickEvent;
import com.auradev.url_shortener.service.ClickIngestService;
import com.auradev.url_shortener.utils.AppMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Consumer theo lô của topic click.
 *
 * <p>Bản tin hỏng (JSON sai, thiếu trường, eventId không phải UUID) không bao giờ thành công dù thử lại,
 * nên được chuyển thẳng sang dead-letter topic và bỏ qua, để không kẹt cả partition. Lỗi hạ tầng khi
 * ghi DB thì ném ra ngoài để container thử lại có back-off rồi mới đẩy cả lô sang DLT
 * (xem {@code KafkaConfig#clickListenerContainerFactory}). Offset chỉ được commit sau khi cả lô xử lý
 * xong, và ghi DB idempotent nên giao lại không gây đếm trùng.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickEventConsumer {

    private static final long DLT_SEND_TIMEOUT_SECONDS = 10;

    /** Số span link tối đa mỗi lô (giới hạn mặc định của SDK là 128). */
    private static final int MAX_LINKS = 50;

    /** Đọc header {@code traceparent} do producer gắn vào record Kafka. */
    private static final TextMapGetter<Header> TRACEPARENT_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Header carrier) {
            return List.of("traceparent");
        }

        @Override
        public String get(Header carrier, String key) {
            return carrier == null ? null : new String(carrier.value(), StandardCharsets.UTF_8);
        }
    };

    private final ObjectMapper                  objectMapper;
    private final ClickIngestService            ingestService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ClickProperties               properties;
    private final AppMetrics                    metrics;
    private final OpenTelemetry                 openTelemetry;

    private Tracer tracer;

    @PostConstruct
    void initTracer() {
        this.tracer = openTelemetry.getTracer("com.auradev.url_shortener.click-consumer");
    }

    @KafkaListener(
            topics = "${app.click.topic:url-clicks}",
            containerFactory = "clickListenerContainerFactory")
    public void onClicks(List<ConsumerRecord<String, String>> records) throws Exception {
        // Spring Kafka không tạo observation cho listener theo lô, và một lô gom nhiều request nên không có
        // một trace cha duy nhất. Thay vào đó mỗi lô là một span "click.ingest" (cha của các span JDBC bên
        // dưới) kèm span link tới trace của từng click, để từ công cụ xem trace lần ngược về request gốc.
        Span span = tracer.spanBuilder("click.ingest").setSpanKind(SpanKind.CONSUMER)
                .setAttribute("messaging.system", "kafka")
                .setAttribute("messaging.destination.name", properties.getTopic())
                .setAttribute("messaging.batch.message_count", records.size())
                .startSpan();
        addLinks(span, records);
        try (Scope ignored = span.makeCurrent()) {
            process(records);
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }

    private void addLinks(Span span, List<ConsumerRecord<String, String>> records) {
        int linked = 0;
        for (ConsumerRecord<String, String> record : records) {
            if (linked >= MAX_LINKS) {
                break;
            }
            Header header = record.headers().lastHeader("traceparent");
            if (header == null) {
                continue;
            }
            SpanContext origin = Span.fromContext(W3CTraceContextPropagator.getInstance().extract(
                    Context.root(), header, TRACEPARENT_GETTER)).getSpanContext();
            if (origin.isValid()) {
                span.addLink(origin);
                linked++;
            }
        }
    }

    private void process(List<ConsumerRecord<String, String>> records) throws Exception {
        List<ClickEvent> events = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            ClickEvent event = parse(record.value());
            if (event == null) {
                log.warn("Malformed click event at {}-{}@{}, sending to {}",
                        record.topic(), record.partition(), record.offset(), properties.getDeadLetterTopic());
                metrics.click("dead_letter");
                kafkaTemplate.send(properties.getDeadLetterTopic(), record.key(), record.value())
                        .get(DLT_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } else {
                events.add(event);
            }
        }
        int inserted = ingestService.ingest(events);
        log.debug("Click batch: {} record(s), {} valid, {} inserted", records.size(), events.size(), inserted);
    }

    /** @return event hợp lệ, hoặc {@code null} nếu bản tin không thể xử lý */
    private ClickEvent parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            ClickEvent event = objectMapper.readValue(json, ClickEvent.class);
            if (event.shortCode() == null || event.shortCode().isBlank()
                    || event.clickedAt() == null || event.eventId() == null) {
                return null;
            }
            UUID.fromString(event.eventId());
            return event;
        } catch (Exception e) {
            return null;
        }
    }
}
