package com.auradev.url_shortener.controller;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.service.ClickPublisher;
import com.auradev.url_shortener.service.RedirectService;
import com.auradev.url_shortener.utils.AppMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Trình duyệt được chuyển sang trang của UI cho link có mật khẩu và link chết; client API vẫn nhận lỗi JSON.
 */
class RedirectControllerTest {

    private static final String BROWSER = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    private final RedirectService service = mock(RedirectService.class);
    private final ClickPublisher clicks = mock(ClickPublisher.class);
    private final ShortenerProperties properties = new ShortenerProperties();
    private RedirectController controller;

    @BeforeEach
    void setUp() {
        controller = new RedirectController(service, clicks, new AppMetrics(new SimpleMeterRegistry()), properties);
    }

    private static MockHttpServletRequest request(String accept) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/abc1234");
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        return request;
    }

    private void failWith(ErrorCode code) {
        doThrow(new AppException(code)).when(service).resolveTarget(anyString());
    }

    @Test
    void aBrowserIsSentToTheMatchingUnavailablePageForEachDeadLinkKind() {
        for (Object[] row : new Object[][]{
                {ErrorCode.URL_NOT_FOUND, "/unavailable/not-found"},
                {ErrorCode.URL_GONE, "/unavailable/gone"},
                {ErrorCode.URL_ACCESS_BLOCKED, "/unavailable/blocked"},
        }) {
            failWith((ErrorCode) row[0]);

            ResponseEntity<Void> response = controller.redirect("abc1234", request(BROWSER));

            assertThat(response.getStatusCode()).as(row[0].toString()).isEqualTo(HttpStatus.FOUND);
            assertThat(response.getHeaders().getLocation()).as(row[0].toString()).hasToString((String) row[1]);
            assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        }
        verify(clicks, never()).publish(anyString(), any());
    }

    @Test
    void aBrowserIsSentToTheUnlockPageForAProtectedLink() {
        failWith(ErrorCode.URL_PASSWORD_REQUIRED);

        ResponseEntity<Void> response = controller.redirect("abc1234", request(BROWSER));

        assertThat(response.getHeaders().getLocation()).hasToString("/unlock/abc1234");
    }

    @Test
    void theErrorPageUrlNeverContainsTheRequestedCode() {
        failWith(ErrorCode.URL_NOT_FOUND);

        ResponseEntity<Void> response = controller.redirect("<script>alert(1)</script>", request(BROWSER));

        assertThat(response.getHeaders().getLocation()).hasToString("/unavailable/not-found");
    }

    @Test
    void apiClientsKeepTheOriginalErrorForEveryAcceptHeaderWithoutHtml() {
        for (ErrorCode code : new ErrorCode[]{ErrorCode.URL_NOT_FOUND, ErrorCode.URL_GONE,
                ErrorCode.URL_ACCESS_BLOCKED, ErrorCode.URL_PASSWORD_REQUIRED}) {
            failWith(code);
            for (String accept : new String[]{null, "*/*", "application/json"}) {
                assertThatThrownBy(() -> controller.redirect("abc1234", request(accept)))
                        .as(code + " / " + accept)
                        .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
            }
        }
    }

    @Test
    void errorsWithoutAnUiPageStillPropagateToABrowser() {
        failWith(ErrorCode.UNKNOWN_ERROR);

        assertThatThrownBy(() -> controller.redirect("abc1234", request(BROWSER))).isInstanceOf(AppException.class);
    }

    @Test
    void aConfiguredUiBaseUrlMakesTheLocationAbsoluteAndIgnoresTrailingSlashes() {
        properties.setUiBaseUrl("http://localhost:5173//");
        failWith(ErrorCode.URL_GONE);

        ResponseEntity<Void> response = controller.redirect("abc1234", request(BROWSER));

        assertThat(response.getHeaders().getLocation()).hasToString("http://localhost:5173/unavailable/gone");
    }

    @Test
    void aWorkingLinkStillRedirectsToItsTargetAndCountsTheClick() {
        when(service.resolveTarget("abc1234")).thenReturn("https://example.com/x");

        ResponseEntity<Void> response = controller.redirect("abc1234", request(BROWSER));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("https://example.com/x");
        verify(clicks).publish(anyString(), any());
    }
}
