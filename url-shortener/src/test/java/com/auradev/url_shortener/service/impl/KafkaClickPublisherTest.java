package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.ClickProperties;
import com.auradev.url_shortener.event.ClickEvent;
import com.auradev.url_shortener.service.GeoIpService;
import com.auradev.url_shortener.utils.VisitorHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test đơn vị của {@link KafkaClickPublisher}: nội dung event, quyền riêng tư và đặc biệt là việc
 * publisher không bao giờ chặn request khi broker Kafka không tồn tại.
 */
class KafkaClickPublisherTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T08:30:00Z"), ZoneOffset.UTC);
    private static final String CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ClickProperties properties;
    private VisitorHasher hasher;
    private final GeoIpService geoIpService = mock(GeoIpService.class);

    @BeforeEach
    void setUp() {
        properties = new ClickProperties();
        properties.setTopic("clicks-test");
        properties.setVisitorSecret("unit-test-secret");
        hasher = new VisitorHasher(properties);
    }

    // ===========================
    //  EVENT CONTENT (executor chạy tại chỗ, KafkaTemplate là mock)
    // ===========================

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> mockTemplate() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        return template;
    }

    private KafkaClickPublisher inline(KafkaTemplate<String, String> template) {
        return new KafkaClickPublisher(template, objectMapper, properties, hasher, geoIpService, CLOCK, Runnable::run);
    }

    private MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/abc1234");
        request.setRemoteAddr("203.0.113.77");
        request.addHeader("User-Agent", CHROME_UA);
        return request;
    }

    private ClickEvent sentEvent(KafkaTemplate<String, String> template) throws Exception {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("clicks-test"), eq("abc1234"), json.capture());
        return objectMapper.readValue(json.getValue(), ClickEvent.class);
    }

    @Test
    void sendsOneEventKeyedByShortCodeWithoutTheRawIp() throws Exception {
        KafkaTemplate<String, String> template = mockTemplate();

        inline(template).publish("abc1234", request("GET"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("clicks-test"), eq("abc1234"), json.capture());
        assertThat(json.getValue()).doesNotContain("203.0.113.77");

        ClickEvent event = objectMapper.readValue(json.getValue(), ClickEvent.class);
        assertThat(event.eventId()).matches("[0-9a-f-]{36}");
        assertThat(event.shortCode()).isEqualTo("abc1234");
        assertThat(event.clickedAt()).hasToString("2026-10-11T08:30");
        assertThat(event.userAgent()).isEqualTo(CHROME_UA);
        assertThat(event.visitorHash()).isEqualTo(hasher.hash("203.0.113.77", CHROME_UA, CLOCK.instant().atZone(ZoneOffset.UTC).toLocalDate()));
        assertThat(event.country()).isNull();
    }

    @Test
    void everyEventGetsAUniqueId() throws Exception {
        KafkaTemplate<String, String> template = mockTemplate();
        KafkaClickPublisher publisher = inline(template);

        publisher.publish("abc1234", request("GET"));
        publisher.publish("abc1234", request("GET"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(template, org.mockito.Mockito.times(2)).send(eq("clicks-test"), eq("abc1234"), json.capture());
        List<String> ids = new ArrayList<>();
        for (String value : json.getAllValues()) {
            ids.add(objectMapper.readValue(value, ClickEvent.class).eventId());
        }
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void headRequestsAreNotCounted() {
        KafkaTemplate<String, String> template = mockTemplate();

        inline(template).publish("abc1234", request("HEAD"));

        verify(template, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void postRequestsCountSoThatUnlockIsTracked() throws Exception {
        KafkaTemplate<String, String> template = mockTemplate();

        inline(template).publish("abc1234", request("POST"));

        assertThat(sentEvent(template).shortCode()).isEqualTo("abc1234");
    }

    @Test
    void countryComesFromTheGeoIpLookupOfTheClientIp() throws Exception {
        when(geoIpService.countryOf("203.0.113.77")).thenReturn("VN");
        KafkaTemplate<String, String> template = mockTemplate();

        inline(template).publish("abc1234", request("GET"));

        assertThat(sentEvent(template).country()).isEqualTo("VN");
    }

    @Test
    void clientSentCountryHeadersAreIgnored() throws Exception {
        KafkaTemplate<String, String> template = mockTemplate();
        MockHttpServletRequest request = request("GET");
        request.addHeader("CF-IPCountry", "VN"); // client tự gửi: không bao giờ được tin

        inline(template).publish("abc1234", request);

        assertThat(sentEvent(template).country()).isNull();
    }

    @Test
    void anOversizedUserAgentIsTruncated() throws Exception {
        KafkaTemplate<String, String> template = mockTemplate();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/abc1234");
        request.addHeader("User-Agent", "A".repeat(5000));

        inline(template).publish("abc1234", request);

        assertThat(sentEvent(template).userAgent()).hasSize(256);
    }

    @Test
    void aSendThatThrowsIsCountedAndNeverPropagates() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("boom"));
        KafkaClickPublisher publisher = inline(template);

        publisher.publish("abc1234", request("GET"));

        assertThat(publisher.failedCount()).isEqualTo(1);
    }

    // ===========================
    //  BROKER CHẾT: không được chặn redirect
    // ===========================

    private ExecutorService smallExecutor;
    private DefaultKafkaProducerFactory<String, String> deadFactory;

    @AfterEach
    void tearDown() {
        if (smallExecutor != null) {
            smallExecutor.shutdownNow();
        }
        if (deadFactory != null) {
            deadFactory.destroy();
        }
    }

    private KafkaClickPublisher publisherWithDeadBroker(int queueSize) {
        // Giống cấu hình application-*.yaml, nhưng trỏ tới cổng không có ai lắng nghe
        deadFactory = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 500,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000));
        smallExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), new ThreadPoolExecutor.AbortPolicy());
        return new KafkaClickPublisher(new KafkaTemplate<>(deadFactory), objectMapper, properties, hasher, geoIpService, CLOCK, smallExecutor);
    }

    @Test
    void publishReturnsImmediatelyWhileTheBrokerIsDown() {
        KafkaClickPublisher publisher = publisherWithDeadBroker(100);

        long start = System.nanoTime();
        publisher.publish("abc1234", request("GET"));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // max.block.ms là 500 ms: nếu send chạy trên thread gọi thì phép đo này sẽ >= 500
        assertThat(elapsedMs).isLessThan(250);
    }

    @Test
    void aFullQueueDropsEventsInsteadOfBlockingAndFailuresAreCounted() {
        KafkaClickPublisher publisher = publisherWithDeadBroker(2);

        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            publisher.publish("abc1234", request("GET"));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(elapsedMs).as("50 publishes while the broker is down").isLessThan(1000);
        assertThat(publisher.droppedCount()).isGreaterThan(0);

        // Các event đã vào hàng đợi rốt cuộc thất bại (metadata timeout) và được đếm, không ai bị ném lỗi
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(
                () -> assertThat(publisher.failedCount() + publisher.droppedCount()).isEqualTo(50));
    }
}
