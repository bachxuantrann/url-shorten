package com.auradev.url_shortener;

import com.auradev.url_shortener.dto.request.CreateUrlRequest;
import com.auradev.url_shortener.dto.response.UrlResponse;
import com.auradev.url_shortener.entity.User;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.repository.UserRepository;
import com.auradev.url_shortener.service.UrlService;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nhiều request tạo link cùng lúc không bao giờ được trùng short code.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UrlConcurrencyIntegrationTest {

    @Autowired
    private UrlService urlService;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void concurrentCreatesNeverProduceDuplicateShortCodes() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User owner = userRepository.save(User.builder()
                .username("conc_" + suffix)
                .email("conc_" + suffix + "@test.local")
                .password("x")
                .build());

        int threads = 16;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<List<String>>> futures = new ArrayList<>();

        try {
            for (int t = 0; t < threads; t++) {
                Callable<List<String>> task = () -> {
                    startGate.await();
                    List<String> codes = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        CreateUrlRequest request = new CreateUrlRequest();
                        request.setOriginalUrl("https://example.com/concurrent/" + UUID.randomUUID());
                        UrlResponse created = urlService.create(owner.getId(), request);
                        codes.add(created.getShortCode());
                    }
                    return codes;
                };
                futures.add(pool.submit(task));
            }
            startGate.countDown();

            List<String> all = new ArrayList<>();
            for (Future<List<String>> future : futures) {
                all.addAll(future.get());
            }

            Set<String> distinct = all.stream().collect(Collectors.toSet());
            assertThat(all).hasSize(threads * perThread);
            assertThat(distinct).hasSameSizeAs(all);
            assertThat(urlRepository.findAll().stream().filter(u -> owner.getId().equals(u.getUserId())).count())
                    .isEqualTo(threads * perThread);
        } finally {
            pool.shutdownNow();
        }
    }
}
