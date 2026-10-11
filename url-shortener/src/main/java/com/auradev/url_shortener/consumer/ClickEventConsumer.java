package com.auradev.url_shortener.consumer;

import com.auradev.url_shortener.config.ClickProperties;
import com.auradev.url_shortener.event.ClickEvent;
import com.auradev.url_shortener.service.ClickIngestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

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

    private final ObjectMapper                  objectMapper;
    private final ClickIngestService            ingestService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ClickProperties               properties;

    @KafkaListener(
            topics = "${app.click.topic:url-clicks}",
            containerFactory = "clickListenerContainerFactory")
    public void onClicks(List<ConsumerRecord<String, String>> records) throws Exception {
        List<ClickEvent> events = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            ClickEvent event = parse(record.value());
            if (event == null) {
                log.warn("Malformed click event at {}-{}@{}, sending to {}",
                        record.topic(), record.partition(), record.offset(), properties.getDeadLetterTopic());
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
