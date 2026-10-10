package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.RedirectService;
import com.auradev.url_shortener.utils.ShortCodes;
import com.auradev.url_shortener.utils.UrlCacheService;
import com.auradev.url_shortener.utils.UrlCacheService.CachedUrl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Triển khai {@link RedirectService}: Redis (cache-aside) → Postgres.
 *
 * <p>Bảng quyết định theo trạng thái:
 * <pre>
 *   không tồn tại / mã sai định dạng / mã dành riêng → 404
 *   ACTIVE, còn hạn                                  → redirect
 *   ACTIVE nhưng đã quá expiresAt, EXPIRED           → 410
 *   DELETED                                          → 410
 *   DISABLED                                         → 404
 *   BLOCKED                                          → 403
 * </pre>
 *
 * <p>Method này cố ý không {@code @Transactional}: đường redirect chỉ đọc tối đa một query.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedirectServiceImpl implements RedirectService {

    private final UrlRepository   urlRepository;
    private final UrlCacheService urlCacheService;

    @Override
    public String resolveTarget(String shortCode) {
        // Mã sai định dạng / dành riêng: không cần đụng tới cache hay DB
        if (!ShortCodes.isValidFormat(shortCode) || ShortCodes.isReserved(shortCode)) {
            throw new AppException(ErrorCode.URL_NOT_FOUND, shortCode);
        }

        CachedUrl cached = urlCacheService.get(shortCode).orElseGet(() -> loadAndCache(shortCode));
        return decide(shortCode, cached);
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
        return new CachedUrl(url.getStatus(), url.getExpiresAt(), url.getOriginalUrl());
    }

    private String decide(String shortCode, CachedUrl cached) {
        if (cached.isMissing()) {
            throw new AppException(ErrorCode.URL_NOT_FOUND, shortCode);
        }

        switch (cached.status()) {
            case ACTIVE -> {
                if (cached.expiresAt() != null && !cached.expiresAt().isAfter(LocalDateTime.now())) {
                    throw new AppException(ErrorCode.URL_GONE);
                }
                return cached.originalUrl();
            }
            case EXPIRED, DELETED -> throw new AppException(ErrorCode.URL_GONE);
            case DISABLED         -> throw new AppException(ErrorCode.URL_NOT_FOUND, shortCode);
            case BLOCKED          -> throw new AppException(ErrorCode.URL_ACCESS_BLOCKED);
            default               -> throw new IllegalStateException("Unhandled status " + cached.status());
        }
    }
}
