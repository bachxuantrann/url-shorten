package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.OpenObserveProperties;
import com.auradev.url_shortener.dto.response.MonitoringStatusResponse;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.utils.OpenObserveClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phần dựng SQL và các nhánh không cần mạng của {@link MonitoringServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class MonitoringServiceImplTest {

    @Mock
    private OpenObserveClient client;

    private OpenObserveProperties properties;
    private MonitoringServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new OpenObserveProperties();
        service = new MonitoringServiceImpl(properties, client, Clock.systemUTC());
    }

    @Test
    void whereDefaultsToErrorAndWarnAndKeepsTheOrderGiven() {
        assertThat(service.where(Set.of(), null)).isEqualTo("severity IN ('ERROR', 'WARN')");
        assertThat(service.where(null, "  ")).isEqualTo("severity IN ('ERROR', 'WARN')");
        assertThat(service.where(new LinkedHashSet<>(List.of("debug", " info ")), null)).isEqualTo("severity IN ('DEBUG', 'INFO')");
    }

    @Test
    void whereEscapesSingleQuotesInTheKeywordAndTreatsWildcardsAsPlainText() {
        assertThat(service.where(Set.of("ERROR"), "100% it's _ok_"))
                .isEqualTo("severity IN ('ERROR') AND str_match_ignore_case(body, '100% it''s _ok_')");
    }

    @Test
    void whereRejectsUnknownLevelsAndOverlongKeywords() {
        assertThatThrownBy(() -> service.where(Set.of("FATAL"), null))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.where(Set.of("ERROR') OR 1=1 --"), null)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.where(Set.of("ERROR"), "x".repeat(101))).isInstanceOf(AppException.class);
        assertThat(service.where(Set.of("ERROR"), "x".repeat(100))).contains("x".repeat(100));
    }

    @Test
    void statusWhenNotConfiguredDoesNotCallOpenObserve() {
        when(client.isConfigured()).thenReturn(false);

        MonitoringStatusResponse status = service.status();

        assertThat(status.isConfigured()).isFalse();
        assertThat(status.isReachable()).isFalse();
        assertThat(status.getUiUrl()).isNull();
        verify(client, never()).isReachable();
    }

    @Test
    void aStreamNameThatIsNotASafeIdentifierIsTreatedAsNotConfigured() {
        properties.setLogStream("default\" ; DROP");

        assertThatThrownBy(() -> service.summary(60))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MONITORING_NOT_CONFIGURED));
        assertThatThrownBy(() -> service.logs(Set.of(), null, 60, PageRequest.of(0, 10)))
                .isInstanceOf(AppException.class);
    }
}
