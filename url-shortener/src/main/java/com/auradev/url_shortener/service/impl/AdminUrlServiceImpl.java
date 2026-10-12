package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.dto.response.AdminUrlResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.AbuseReportStatusEnum;
import com.auradev.url_shortener.enums.AppealStatusEnum;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.mapper.UrlMapper;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.AbuseReportService;
import com.auradev.url_shortener.service.AdminUrlService;
import com.auradev.url_shortener.service.LinkAppealService;
import com.auradev.url_shortener.utils.LikePatterns;
import com.auradev.url_shortener.utils.UrlCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

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
    private final Clock               clock;
    private final AbuseReportService  abuseReportService;
    private final LinkAppealService   linkAppealService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminUrlResponse> list(String keyword, UrlStatusEnum status, Pageable pageable) {
        String pattern = LikePatterns.contains(keyword);
        return PageResponse.of(urlRepository.findAllForAdmin(status, pattern, pageable).map(this::toResponse));
    }

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
        // Chặn chính là cách xử lý một báo cáo lạm dụng: các báo cáo đang mở của link coi như đã xử lý
        abuseReportService.closeForUrl(url.getId(), AbuseReportStatusEnum.RESOLVED);

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
        // Gỡ chặn trực tiếp cũng trả lời kháng nghị đang chờ của chủ link (coi như được chấp nhận)
        linkAppealService.closeForUrl(url.getId(), AppealStatusEnum.ACCEPTED);

        log.info("[ADMIN] URL unblocked: shortCode={}", shortCode);
        return toResponse(urlRepository.save(url));
    }

    /** Link đã xoá coi như không tồn tại (mã không bao giờ được cấp lại). */
    private Url find(String shortCode) {
        return urlRepository.findByShortCode(shortCode)
                .filter(url -> url.getStatus() != UrlStatusEnum.DELETED)
                .orElseThrow(() -> new AppException(ErrorCode.URL_NOT_FOUND, shortCode));
    }

    /** Link ACTIVE đã quá hạn nhưng job chưa kịp chuyển được báo là EXPIRED, khớp với kết quả redirect (410). */
    private AdminUrlResponse toResponse(Url url) {
        String baseUrl = shortenerProperties.getBaseUrl();
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        AdminUrlResponse response = urlMapper.toAdminUrlResponse(url, trimmed);
        if (url.getStatus() == UrlStatusEnum.ACTIVE
                && url.getExpiresAt() != null
                && !url.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            response.setStatus(UrlStatusEnum.EXPIRED);
        }
        return response;
    }
}
