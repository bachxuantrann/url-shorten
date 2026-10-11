package com.auradev.url_shortener;

import com.auradev.url_shortener.config.ClickProperties;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.event.ClickEvent;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.ClickIngestService;
import com.auradev.url_shortener.support.ApiClient;
import com.auradev.url_shortener.support.ApiClient.Resp;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import com.auradev.url_shortener.utils.UrlCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Test end-to-end theo dõi click trên Postgres/Redis/Kafka thật: redirect → Kafka → consumer → url_clicks.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClickTrackingIntegrationTest {

    private static final String CHROME_ON_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    @LocalServerPort
    private int port;

    @Autowired private UrlRepository          urlRepository;
    @Autowired private UrlCacheService        urlCacheService;
    @Autowired private JdbcTemplate           jdbc;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private ObjectMapper           objectMapper;
    @Autowired private ClickProperties        clickProperties;
    @Autowired private ClickIngestService     ingestService;
    @Autowired private EntityManagerFactory   entityManagerFactory;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private ApiClient api;
    private String owner;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(port);
        owner = api.registerAndLogin();
    }

    // ===========================
    //  REDIRECT → CLICK
    // ===========================

    @Test
    void aSuccessfulRedirectIsRecordedOnceWithEnrichedFieldsAndNoRawIp() throws Exception {
        String code = api.createUrl(owner, "https://example.com/tracked");
        String ip = uniqueIp();

        Resp resp = get(code, ip, CHROME_ON_WINDOWS);
        assertThat(resp.status()).isEqualTo(302);

        awaitClicks(code, 1);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT c.visitor_hash, c.country, c.device_type, c.browser, c.os
                FROM url_clicks c JOIN urls u ON u.id = c.url_id WHERE u.short_code = ?
                """, code);
        assertThat(row.get("device_type")).isEqualTo("DESKTOP");
        assertThat(row.get("browser")).isEqualTo("Chrome");
        assertThat(row.get("os")).isEqualTo("Windows");
        assertThat(row.get("country")).isNull();
        assertThat((String) row.get("visitor_hash")).matches("[0-9a-f]{32}").doesNotContain(ip);
        assertThat(clickCount(code)).isEqualTo(1);
    }

    @Test
    void severalClicksAreCountedAndFromTheSameVisitorShareTheHash() throws Exception {
        String code = api.createUrl(owner, "https://example.com/many");
        String ip = uniqueIp();

        for (int i = 0; i < 3; i++) {
            assertThat(get(code, ip, CHROME_ON_WINDOWS).status()).isEqualTo(302);
        }
        assertThat(get(code, uniqueIp(), CHROME_ON_WINDOWS).status()).isEqualTo(302);

        awaitClicks(code, 4);
        assertThat(clickCount(code)).isEqualTo(4);
        Long distinctVisitors = jdbc.queryForObject("""
                SELECT count(DISTINCT c.visitor_hash) FROM url_clicks c JOIN urls u ON u.id = c.url_id
                WHERE u.short_code = ?
                """, Long.class, code);
        assertThat(distinctVisitors).isEqualTo(2);
    }

    @Test
    void headRequestsAreNotCounted() throws Exception {
        String code = api.createUrl(owner, "https://example.com/head-tracked");
        String sentinel = api.createUrl(owner, "https://example.com/head-sentinel");

        assertThat(call("HEAD", code, uniqueIp()).status()).isEqualTo(302);
        assertThat(get(sentinel, uniqueIp(), CHROME_ON_WINDOWS).status()).isEqualTo(302);

        awaitClicks(sentinel, 1);
        pause();
        assertThat(clicks(code)).isZero();
    }

    @Test
    void failedRedirectsAreNeverCounted() throws Exception {
        String disabled = api.createUrl(owner, "https://example.com/d");
        api.call("PUT", "/api/v1/urls/" + disabled + "/disable", owner, null);
        String deleted = api.createUrl(owner, "https://example.com/x");
        api.call("DELETE", "/api/v1/urls/" + deleted, owner, null);
        String blocked = api.createUrl(owner, "https://example.com/b");
        api.call("PUT", "/api/v1/admin/urls/" + blocked + "/block", api.loginAsAdmin(), "{\"reason\":\"test\"}");
        String expired = api.createUrl(owner, "https://example.com/e");
        jdbc.update("UPDATE urls SET expires_at = ? WHERE short_code = ?", LocalDateTime.now().minusDays(1), expired);
        urlCacheService.evict(expired);
        String protectedLink = api.call("POST", "/api/v1/urls", owner,
                "{\"originalUrl\":\"https://example.com/p\",\"password\":\"pw-1234\"}").body().get("data").get("shortCode").asText();
        String sentinel = api.createUrl(owner, "https://example.com/sentinel");

        assertThat(get(disabled, uniqueIp(), CHROME_ON_WINDOWS).status()).isEqualTo(404);
        assertThat(get(deleted, uniqueIp(), CHROME_ON_WINDOWS).status()).isEqualTo(410);
        assertThat(get(blocked, uniqueIp(), CHROME_ON_WINDOWS).status()).isEqualTo(403);
        assertThat(get(expired, uniqueIp(), CHROME_ON_WINDOWS).status()).isEqualTo(410);
        assertThat(get(protectedLink, uniqueIp(), CHROME_ON_WINDOWS).status()).isEqualTo(403);
        assertThat(get("nosuch" + shortId(), uniqueIp(), CHROME_ON_WINDOWS).status()).isEqualTo(404);
        assertThat(get(sentinel, uniqueIp(), CHROME_ON_WINDOWS).status()).isEqualTo(302);

        awaitClicks(sentinel, 1);
        pause();
        for (String code : List.of(disabled, deleted, blocked, expired, protectedLink)) {
            assertThat(clicks(code)).as("clicks of %s", code).isZero();
        }
    }

    @Test
    void unlockingAProtectedLinkCountsOnlyWhenThePasswordIsRight() throws Exception {
        String code = api.call("POST", "/api/v1/urls", owner,
                "{\"originalUrl\":\"https://example.com/secret\",\"password\":\"right-pw\"}")
                .body().get("data").get("shortCode").asText();

        assertThat(unlock(code, "wrong-pw").status()).isEqualTo(403);
        assertThat(unlock(code, "right-pw").status()).isEqualTo(200);

        awaitClicks(code, 1);
        pause();
        assertThat(clicks(code)).isEqualTo(1);
    }

    // ===========================
    //  IDEMPOTENCY
    // ===========================

    @Test
    void aRedeliveredEventIsStoredAndCountedOnlyOnce() throws Exception {
        String code = persistedLink();
        ClickEvent event = event(code, LocalDateTime.now());

        send(code, event);
        send(code, event); // Kafka giao lại cùng eventId
        awaitClicks(code, 1);
        pause();

        assertThat(clicks(code)).isEqualTo(1);
        assertThat(clickCount(code)).isEqualTo(1);
        // Và ghi lại trực tiếp lần nữa cũng không đổi gì
        assertThat(ingestService.ingest(List.of(event))).isZero();
        assertThat(clickCount(code)).isEqualTo(1);
    }

    @Test
    void duplicatesInsideOneBatchAreCountedOnce() {
        String code = persistedLink();
        ClickEvent event = event(code, LocalDateTime.now());

        int inserted = ingestService.ingest(List.of(event, event, event));

        assertThat(inserted).isEqualTo(1);
        assertThat(clicks(code)).isEqualTo(1);
        assertThat(clickCount(code)).isEqualTo(1);
    }

    @Test
    void clickCountEqualsTheNumberOfRowsActuallyInserted() {
        String code = persistedLink();
        ClickEvent first = event(code, LocalDateTime.now());
        ingestService.ingest(List.of(first));

        // Lô gồm 1 event đã có và 2 event mới
        int inserted = ingestService.ingest(List.of(first, event(code, LocalDateTime.now()), event(code, LocalDateTime.now())));

        assertThat(inserted).isEqualTo(2);
        assertThat(clicks(code)).isEqualTo(3);
        assertThat(clickCount(code)).isEqualTo(3);
    }

    @Test
    void eventsForUnknownCodesAreSkippedWithoutBlockingTheBatch() {
        String code = persistedLink();

        int inserted = ingestService.ingest(List.of(
                event("ghost" + shortId(), LocalDateTime.now()),
                event(code, LocalDateTime.now())));

        assertThat(inserted).isEqualTo(1);
        assertThat(clicks(code)).isEqualTo(1);
    }

    @Test
    void clicksOfASoftDeletedLinkAreStillRecorded() {
        String code = persistedLink();
        jdbc.update("UPDATE urls SET status = 'DELETED', deleted_at = now() WHERE short_code = ?", code);

        assertThat(ingestService.ingest(List.of(event(code, LocalDateTime.now())))).isEqualTo(1);
    }

    // ===========================
    //  POISON MESSAGES
    // ===========================

    @Test
    void aMalformedMessageGoesToTheDeadLetterTopicAndDoesNotBlockValidEvents() throws Exception {
        String code = persistedLink();
        String poisonKey = "poison-" + shortId();

        kafkaTemplate.send(clickProperties.getTopic(), poisonKey, "this is {not json").get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(clickProperties.getTopic(), poisonKey, "{\"eventId\":\"not-a-uuid\",\"shortCode\":\"" + code
                + "\",\"clickedAt\":\"2026-10-11T08:30:00\"}").get(10, TimeUnit.SECONDS);
        send(code, event(code, LocalDateTime.now()));

        awaitClicks(code, 1);
        List<ConsumerRecord<String, String>> dead = readDeadLetters(poisonKey, 2);
        assertThat(dead).extracting(ConsumerRecord::value)
                .anyMatch(v -> v.equals("this is {not json"))
                .anyMatch(v -> v.contains("not-a-uuid"));
        assertThat(clicks(code)).isEqualTo(1); // event hợp lệ vẫn được ghi, event có eventId sai thì không
    }

    // ===========================
    //  PARTITIONING
    // ===========================

    @Test
    void anEventOutsideEveryPartitionLandsInTheDefaultPartitionInsteadOfBeingLost() {
        String code = persistedLink();

        // Năm 2001: chắc chắn không có phân vùng tháng nào (kể cả do test phân vùng tạo ra)
        int inserted = ingestService.ingest(List.of(event(code, LocalDateTime.of(2001, 2, 3, 4, 5))));

        assertThat(inserted).isEqualTo(1);
        String partition = jdbc.queryForObject("""
                SELECT c.tableoid::regclass::text FROM url_clicks c JOIN urls u ON u.id = c.url_id WHERE u.short_code = ?
                """, String.class, code);
        assertThat(partition).isEqualTo("url_clicks_default");
    }

    @Test
    void currentMonthEventsGoToTheirMonthlyPartition() {
        String code = persistedLink();
        LocalDateTime now = LocalDateTime.now();

        ingestService.ingest(List.of(event(code, now)));

        String partition = jdbc.queryForObject("""
                SELECT c.tableoid::regclass::text FROM url_clicks c JOIN urls u ON u.id = c.url_id WHERE u.short_code = ?
                """, String.class, code);
        assertThat(partition).isEqualTo(String.format("url_clicks_y%04dm%02d", now.getYear(), now.getMonthValue()));
    }

    // ===========================
    //  N+1: số câu lệnh không phụ thuộc kích thước lô
    // ===========================

    @Test
    void ingestingABatchIssuesAConstantNumberOfStatements() {
        // Tạo link trước: chỉ phần ingest được đo
        List<ClickEvent> threeEvents = eventsForNewLinks(3);
        List<ClickEvent> fortyEvents = eventsForNewLinks(40);

        long few = countStatements(() -> ingestService.ingest(threeEvents));
        long many = countStatements(() -> ingestService.ingest(fortyEvents));

        assertThat(few).as("statements for 3 links").isPositive();
        assertThat(many).as("statements for 40 links").isEqualTo(few);
    }

    @Test
    void aBatchAcrossManyLinksUpdatesEachLinkCounter() {
        List<ClickEvent> events = eventsForNewLinks(25);

        assertThat(ingestService.ingest(events)).isEqualTo(25);

        for (ClickEvent event : events) {
            assertThat(clickCount(event.shortCode())).as(event.shortCode()).isEqualTo(1);
        }
    }

    // ===========================
    //  HELPERS
    // ===========================

    private Resp get(String code, String ip, String userAgent) throws Exception {
        return api.call("GET", "/" + code, null, null, Map.of("X-Forwarded-For", ip, "User-Agent", userAgent));
    }

    private Resp call(String method, String code, String ip) throws Exception {
        return api.call(method, "/" + code, null, null, Map.of("X-Forwarded-For", ip));
    }

    private Resp unlock(String code, String password) throws Exception {
        return api.call("POST", "/api/v1/public/urls/" + code + "/unlock", null,
                "{\"password\":\"" + password + "\"}", Map.of("X-Forwarded-For", uniqueIp()));
    }

    /** Tạo link trực tiếp trong DB (không qua API) cho các test ghi thẳng event. */
    private String persistedLink() {
        String code = "c" + shortId() + "x";
        urlRepository.save(Url.builder()
                .shortCode(code)
                .originalUrl("https://example.com/" + code)
                .status(UrlStatusEnum.ACTIVE)
                .build());
        return code;
    }

    private List<ClickEvent> eventsForNewLinks(int count) {
        List<ClickEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(event(persistedLink(), LocalDateTime.now()));
        }
        return events;
    }

    private ClickEvent event(String code, LocalDateTime at) {
        return new ClickEvent(UUID.randomUUID().toString(), code, at, "0123456789abcdef0123456789abcdef", null, CHROME_ON_WINDOWS);
    }

    private void send(String key, ClickEvent event) throws Exception {
        kafkaTemplate.send(clickProperties.getTopic(), key, objectMapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
    }

    private void awaitClicks(String code, long expected) {
        await().atMost(30, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(clicks(code)).isEqualTo(expected));
    }

    /** Thời gian chờ để chắc chắn một event "không được ghi" thật sự không được ghi (không phải chỉ chưa kịp). */
    private void pause() throws InterruptedException {
        Thread.sleep(1500);
    }

    private long clicks(String code) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM url_clicks c JOIN urls u ON u.id = c.url_id WHERE u.short_code = ?
                """, Long.class, code);
        return count == null ? 0 : count;
    }

    private long clickCount(String code) {
        Long count = jdbc.queryForObject("SELECT click_count FROM urls WHERE short_code = ?", Long.class, code);
        return count == null ? 0 : count;
    }

    private List<ConsumerRecord<String, String>> readDeadLetters(String key, int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(clickProperties.getDeadLetterTopic()));
            await().atMost(30, TimeUnit.SECONDS).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
                return found.size() >= expected;
            });
        }
        return found;
    }

    private long countStatements(Supplier<?> action) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            action.get();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    /** IP giả lập duy nhất cho mỗi lần gọi (Tomcat tin X-Forwarded-For vì kết nối test đến từ loopback). */
    private String uniqueIp() {
        long bits = UUID.randomUUID().getMostSignificantBits();
        return "198.51." + ((bits >>> 8) & 0xff) + "." + (bits & 0xff);
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
