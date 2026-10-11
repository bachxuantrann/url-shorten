package com.auradev.url_shortener;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.entity.User;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.repository.UserRepository;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Chứng minh {@code @Scheduled} thật sự tự chạy job (không ai gọi trực tiếp) khi đặt chu kỳ ngắn.
 * Dùng context riêng vì ghi đè {@code app.shortener.expiry-job-interval}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.shortener.expiry-job-interval=PT1S")
class UrlExpirationSchedulingTest {

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void schedulerMovesDueLinksToExpiredWithoutAnyManualCall() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User owner = userRepository.save(User.builder()
                .username("sched_" + suffix).email("sched_" + suffix + "@test.local").password("x").build());
        Url due = urlRepository.save(Url.builder()
                .userId(owner.getId())
                .shortCode("s" + suffix)
                .originalUrl("https://example.com/scheduled")
                .status(UrlStatusEnum.ACTIVE)
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(urlRepository.findById(due.getId()).orElseThrow().getStatus())
                        .isEqualTo(UrlStatusEnum.EXPIRED));
    }
}
