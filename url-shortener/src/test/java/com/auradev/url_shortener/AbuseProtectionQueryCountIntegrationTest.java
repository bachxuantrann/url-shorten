package com.auradev.url_shortener;

import com.auradev.url_shortener.dto.request.AddBlockedDomainRequest;
import com.auradev.url_shortener.dto.response.BlockedDomainResponse;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.BlockedDomainService;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import com.auradev.url_shortener.utils.UrlTargetPolicy;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Các thao tác chống lạm dụng không được phát sinh số câu SQL tăng theo dữ liệu (không N+1).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AbuseProtectionQueryCountIntegrationTest {

    @Autowired
    private UrlTargetPolicy urlTargetPolicy;

    @Autowired
    private BlockedDomainService blockedDomainService;

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void checkingATargetAgainstTheBlocklistIsASingleQueryHoweverDeepTheHost() {
        long shallow = countStatements(() -> urlTargetPolicy.check("https://example.com/x"));
        long deep = countStatements(() -> urlTargetPolicy.check("https://a.b.c.d.e.example.com/x"));

        assertThat(shallow).isEqualTo(1);
        assertThat(deep).isEqualTo(1);
    }

    @Test
    void blockingExistingLinksOfADomainIssuesTheSameStatementsForOneLinkOrThirty() {
        String manyLinksDomain = "many" + shortId() + ".com";
        String noLinksDomain = "none" + shortId() + ".com";
        for (int i = 0; i < 30; i++) {
            anonymousLink("https://" + manyLinksDomain + "/page/" + i);
        }

        long withLinks = countStatements(() -> add(manyLinksDomain));
        long withoutLinks = countStatements(() -> add(noLinksDomain));

        assertThat(withLinks).as("statements to block 30 links vs 0 links").isEqualTo(withoutLinks);
        assertThat(withLinks).isLessThanOrEqualTo(5);
        assertThat(urlRepository.findAll().stream()
                .filter(u -> u.getOriginalUrl().contains(manyLinksDomain))
                .map(Url::getStatus)
                .distinct()
                .toList())
                .containsExactly(UrlStatusEnum.BLOCKED);
    }

    private BlockedDomainResponse add(String domain) {
        AddBlockedDomainRequest request = new AddBlockedDomainRequest();
        request.setDomain(domain);
        request.setBlockExistingLinks(true);
        return blockedDomainService.add(request);
    }

    private void anonymousLink(String originalUrl) {
        urlRepository.save(Url.builder()
                .shortCode("q" + UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .originalUrl(originalUrl)
                .status(UrlStatusEnum.ACTIVE)
                .build());
    }

    private long countStatements(Runnable action) {
        return countStatements(() -> {
            action.run();
            return null;
        });
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

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
