package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.dto.request.CreateUrlRequest;
import com.auradev.url_shortener.dto.request.UpdateUrlRequest;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.dto.response.UrlResponse;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.mapper.UrlMapper;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.UrlService;
import com.auradev.url_shortener.utils.ShortCodeGenerator;
import com.auradev.url_shortener.utils.ShortCodes;
import com.auradev.url_shortener.utils.UrlCacheService;
import com.auradev.url_shortener.utils.UrlUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Triển khai {@link UrlService}.
 *
 * <p>Vòng đời trạng thái (do chủ link thao tác):
 * <pre>
 *   ACTIVE  ⇄ DISABLED          (disable / enable)
 *   EXPIRED → ACTIVE            (update với hạn mới)
 *   *       → DELETED           (delete, trừ BLOCKED)
 *   BLOCKED                     (chủ link không được sửa/tắt/xoá)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrlServiceImpl implements UrlService {

    private final UrlRepository       urlRepository;
    private final UrlMapper           urlMapper;
    private final ShortCodeGenerator  shortCodeGenerator;
    private final ShortenerProperties shortenerProperties;
    private final UrlCacheService     urlCacheService;

    // ===========================
    //  CREATE
    // ===========================

    @Override
    @Transactional
    public UrlResponse create(UUID userId, CreateUrlRequest request) {
        String originalUrl = UrlUtils.validate(request.getOriginalUrl());

        // Số tuần tự duy nhất từ Postgres → mã duy nhất, an toàn khi chạy nhiều instance.
        // Bỏ qua (cực hiếm) mã trùng với từ khoá dành riêng như "swagger".
        String shortCode;
        do {
            shortCode = shortCodeGenerator.generate(urlRepository.nextShortCodeSequence());
        } while (ShortCodes.isReserved(shortCode));

        Url saved = urlRepository.save(Url.builder()
                .userId(userId)
                .shortCode(shortCode)
                .originalUrl(originalUrl)
                .status(UrlStatusEnum.ACTIVE)
                .expiresAt(request.getExpiresAt())
                .build());

        // Xoá negative cache (nếu có ai đã truy cập mã này trước khi nó được tạo)
        urlCacheService.evictAfterCommit(saved.getShortCode());

        log.info("URL created: shortCode={}, userId={}", saved.getShortCode(), userId);
        return toResponse(saved);
    }

    // ===========================
    //  READ
    // ===========================

    @Override
    @Transactional(readOnly = true)
    public UrlResponse get(UUID userId, String shortCode) {
        return toResponse(findOwned(userId, shortCode));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UrlResponse> list(UUID userId, String keyword, UrlStatusEnum status, Pageable pageable) {
        String pattern = toLikePattern(keyword);
        return PageResponse.of(urlRepository.findOwnedByUser(userId, status, pattern, pageable).map(this::toResponse));
    }

    // ===========================
    //  UPDATE
    // ===========================

    @Override
    @Transactional
    public UrlResponse update(UUID userId, String shortCode, UpdateUrlRequest request) {
        Url url = findOwned(userId, shortCode);
        assertNotBlocked(url);

        url.setOriginalUrl(UrlUtils.validate(request.getOriginalUrl()));
        url.setExpiresAt(request.getExpiresAt());

        // Hạn mới (đã validate ở tương lai hoặc bỏ hạn) → link hết hạn được bật lại
        if (url.getStatus() == UrlStatusEnum.EXPIRED) {
            url.setStatus(UrlStatusEnum.ACTIVE);
        }

        urlCacheService.evictAfterCommit(shortCode);

        log.info("URL updated: shortCode={}, userId={}", shortCode, userId);
        return toResponse(urlRepository.save(url));
    }

    @Override
    @Transactional
    public UrlResponse disable(UUID userId, String shortCode) {
        Url url = findOwned(userId, shortCode);
        assertNotBlocked(url);

        switch (url.getStatus()) {
            case ACTIVE   -> url.setStatus(UrlStatusEnum.DISABLED);
            case DISABLED -> { /* đã tắt — idempotent */ }
            default       -> throw new AppException(ErrorCode.URL_INVALID_STATE, url.getStatus());
        }

        urlCacheService.evictAfterCommit(shortCode);

        log.info("URL disabled: shortCode={}, userId={}", shortCode, userId);
        return toResponse(urlRepository.save(url));
    }

    @Override
    @Transactional
    public UrlResponse enable(UUID userId, String shortCode) {
        Url url = findOwned(userId, shortCode);
        assertNotBlocked(url);

        switch (url.getStatus()) {
            case DISABLED -> {
                if (url.getExpiresAt() != null && !url.getExpiresAt().isAfter(LocalDateTime.now())) {
                    throw new AppException(ErrorCode.URL_EXPIRED);
                }
                url.setStatus(UrlStatusEnum.ACTIVE);
            }
            case ACTIVE -> { /* đã bật — idempotent */ }
            default     -> throw new AppException(ErrorCode.URL_INVALID_STATE, url.getStatus());
        }

        urlCacheService.evictAfterCommit(shortCode);

        log.info("URL enabled: shortCode={}, userId={}", shortCode, userId);
        return toResponse(urlRepository.save(url));
    }

    // ===========================
    //  DELETE (soft)
    // ===========================

    @Override
    @Transactional
    public void delete(UUID userId, String shortCode) {
        Url url = findOwned(userId, shortCode);
        assertNotBlocked(url);

        url.setStatus(UrlStatusEnum.DELETED);
        url.setDeletedAt(LocalDateTime.now());
        urlRepository.save(url);
        urlCacheService.evictAfterCommit(shortCode);

        log.info("URL deleted: shortCode={}, userId={}", shortCode, userId);
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    /** Link thuộc user và chưa bị xoá; ngược lại coi như không tồn tại. */
    private Url findOwned(UUID userId, String shortCode) {
        return urlRepository
                .findByShortCodeAndUserIdAndStatusNot(shortCode, userId, UrlStatusEnum.DELETED)
                .orElseThrow(() -> new AppException(ErrorCode.URL_NOT_FOUND, shortCode));
    }

    private void assertNotBlocked(Url url) {
        if (url.getStatus() == UrlStatusEnum.BLOCKED) {
            throw new AppException(ErrorCode.URL_BLOCKED);
        }
    }

    /**
     * Chuyển từ khoá người dùng nhập thành mẫu LIKE an toàn: hạ chữ thường, escape {@code !}, {@code %}, {@code _}
     * (ký tự escape khai báo trong query là {@code !}) để người dùng không tự chèn wildcard.
     * Từ khoá rỗng cho ra {@code "%"} (khớp tất cả).
     */
    private String toLikePattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "%";
        }
        String escaped = keyword.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private UrlResponse toResponse(Url url) {
        return urlMapper.toUrlResponse(url, baseUrl());
    }

    private String baseUrl() {
        String baseUrl = shortenerProperties.getBaseUrl();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
