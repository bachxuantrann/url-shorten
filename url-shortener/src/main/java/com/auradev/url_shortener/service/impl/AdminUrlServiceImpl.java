package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.dto.response.AdminUrlResponse;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.mapper.UrlMapper;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.AdminUrlService;
import com.auradev.url_shortener.utils.UrlCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Triển khai {@link AdminUrlService}. Cập nhật qua entity nên auditing ghi {@code updatedBy} là admin
 * thực hiện; mọi thay đổi trạng thái đều xoá cache redirect sau khi commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUrlServiceImpl implements AdminUrlService {

    private final UrlRepository       urlRepository;
    private final UrlMapper           urlMapper;
    private final UrlCacheService     urlCacheService;
    private final ShortenerProperties shortenerProperties;

    @Override
    @Transactional(readOnly = true)
    public AdminUrlResponse getUrl(String shortCode) {
        return toResponse(find(shortCode));
    }

    @Override
    @Transactional
    public AdminUrlResponse blockUrl(String shortCode, String reason) {
        Url url = find(shortCode);

        url.setStatus(UrlStatusEnum.BLOCKED);
        url.setBlockedReason(reason.trim());
        urlCacheService.evictAfterCommit(shortCode);

        log.info("[ADMIN] URL blocked: shortCode={}, reason={}", shortCode, reason);
        return toResponse(urlRepository.save(url));
    }

    @Override
    @Transactional
    public AdminUrlResponse unblockUrl(String shortCode) {
        Url url = find(shortCode);
        if (url.getStatus() != UrlStatusEnum.BLOCKED) {
            throw new AppException(ErrorCode.URL_INVALID_STATE, url.getStatus());
        }

        url.setStatus(UrlStatusEnum.ACTIVE);
        url.setBlockedReason(null);
        urlCacheService.evictAfterCommit(shortCode);

        log.info("[ADMIN] URL unblocked: shortCode={}", shortCode);
        return toResponse(urlRepository.save(url));
    }

    /** Link đã xoá coi như không tồn tại (mã không bao giờ được cấp lại). */
    private Url find(String shortCode) {
        return urlRepository.findByShortCode(shortCode)
                .filter(url -> url.getStatus() != UrlStatusEnum.DELETED)
                .orElseThrow(() -> new AppException(ErrorCode.URL_NOT_FOUND, shortCode));
    }

    private AdminUrlResponse toResponse(Url url) {
        String baseUrl = shortenerProperties.getBaseUrl();
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return urlMapper.toAdminUrlResponse(url, trimmed);
    }
}
