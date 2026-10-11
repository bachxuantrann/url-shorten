package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.RedirectService;
import com.auradev.url_shortener.utils.ShortCodes;
import com.auradev.url_shortener.utils.UrlCacheService;
import com.auradev.url_shortener.utils.UrlCacheService.CachedUrl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Triển khai {@link RedirectService}: Redis (cache-aside) → Postgres.
 *
 * <p>Bảng quyết định theo trạng thái:
 * <pre>
 *   không tồn tại / mã sai định dạng / mã dành riêng → 404
 *   ACTIVE, còn hạn, không mật khẩu                  → redirect
 *   ACTIVE, còn hạn, có mật khẩu                     → 403 URL_PASSWORD_REQUIRED (phải unlock)
 *   ACTIVE nhưng đã quá expiresAt, EXPIRED           → 410
 *   DELETED                                          → 410
 *   DISABLED                                         → 404
 *   BLOCKED                                          → 403 URL_ACCESS_BLOCKED
 * </pre>
 * Thứ tự: trạng thái/hết hạn được xét trước mật khẩu, nên link đã tắt/hết hạn/bị chặn không bao giờ
 * tiết lộ rằng nó có mật khẩu và không tốn CPU băm.
 *
 * <p>{@code resolveTarget} cố ý không {@code @Transactional}: đường redirect chỉ đọc tối đa một query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedirectServiceImpl implements RedirectService {

    private final UrlRepository   urlRepository;
    private final UrlCacheService urlCacheService;
    private final Clock           clock;
    private final PasswordEncoder passwordEncoder;

    @Override
    public String resolveTarget(String shortCode) {
        // Mã sai định dạng / dành riêng: không cần đụng tới cache hay DB
        if (!ShortCodes.isResolvable(shortCode)) {
            throw new AppException(ErrorCode.URL_NOT_FOUND, shortCode);
        }

        CachedUrl cached = urlCacheService.get(shortCode).orElseGet(() -> loadAndCache(shortCode));
        if (cached.isMissing()) {
            throw new AppException(ErrorCode.URL_NOT_FOUND, shortCode);
        }

        assertAvailable(shortCode, cached.status(), cached.expiresAt());
        if (cached.passwordProtected()) {
            throw new AppException(ErrorCode.URL_PASSWORD_REQUIRED);
        }
        return cached.originalUrl();
    }

    @Override
    public String unlockTarget(String shortCode, String password) {
        if (!ShortCodes.isResolvable(shortCode)) {
            throw new AppException(ErrorCode.URL_NOT_FOUND, shortCode);
        }

        Url url = urlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new AppException(ErrorCode.URL_NOT_FOUND, shortCode));
        assertAvailable(shortCode, url.getStatus(), url.getExpiresAt());

        String hash = url.getPasswordHash();
        if (hash == null) {
            return url.getOriginalUrl(); // không có gì để mở khoá
        }
        if (password == null || !passwordEncoder.matches(password, hash)) {
            throw new AppException(ErrorCode.URL_PASSWORD_INCORRECT);
        }
        return url.getOriginalUrl();
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    /** Cache miss (hoặc Redis lỗi): đọc DB rồi nạp lại cache, kể cả kết quả "không tồn tại". */
    private CachedUrl loadAndCache(String shortCode) {
        Optional<Url> found = urlRepository.findByShortCode(shortCode);
        if (found.isEmpty()) {
            urlCacheService.putMissing(shortCode);
            return UrlCacheService.MISSING;
        }
        Url url = found.get();
        urlCacheService.put(shortCode, url);

        boolean passwordProtected = url.getPasswordHash() != null;
        return new CachedUrl(url.getStatus(), url.getExpiresAt(), passwordProtected,
                passwordProtected ? "" : url.getOriginalUrl());
    }

    /** Ném lỗi tương ứng nếu link không ở trạng thái cho phép truy cập (trừ yêu cầu mật khẩu). */
    private void assertAvailable(String shortCode, UrlStatusEnum status, LocalDateTime expiresAt) {
        switch (status) {
            case ACTIVE -> {
                if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now(clock))) {
                    throw new AppException(ErrorCode.URL_GONE);
                }
            }
            case EXPIRED, DELETED -> throw new AppException(ErrorCode.URL_GONE);
            case DISABLED         -> throw new AppException(ErrorCode.URL_NOT_FOUND, shortCode);
            case BLOCKED          -> throw new AppException(ErrorCode.URL_ACCESS_BLOCKED);
            default               -> throw new IllegalStateException("Unhandled status " + status);
        }
    }
}
