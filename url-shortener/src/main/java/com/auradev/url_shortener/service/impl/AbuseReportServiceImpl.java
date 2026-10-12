package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.AuditorAwareImpl;
import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.dto.response.AbuseReportDetailResponse;
import com.auradev.url_shortener.dto.response.AbuseReportSummaryResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.AbuseReasonEnum;
import com.auradev.url_shortener.enums.AbuseReportStatusEnum;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.repository.AbuseReportRepository;
import com.auradev.url_shortener.repository.LinkReportSummary;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.AbuseReportService;
import com.auradev.url_shortener.utils.VisitorHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Triển khai {@link AbuseReportService}. Số câu lệnh SQL của mỗi thao tác là hằng số: ghi một báo cáo, đóng báo
 * cáo hay liệt kê (kèm thống kê lý do của cả trang) đều không phụ thuộc số báo cáo.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AbuseReportServiceImpl implements AbuseReportService {

    private final AbuseReportRepository abuseReportRepository;
    private final UrlRepository         urlRepository;
    private final VisitorHasher         visitorHasher;
    private final AuditorAwareImpl      auditorAware;
    private final ShortenerProperties   shortenerProperties;
    private final Clock                 clock;

    @Override
    @Transactional
    public void submit(String shortCode, AbuseReasonEnum reason, String details, String ip) {
        Url url = find(shortCode);
        if (url.getStatus() == UrlStatusEnum.BLOCKED) {
            return; // đã bị chặn, không cần admin xem xét thêm
        }

        String trimmed = StringUtils.hasText(details) ? details.trim() : null;
        int stored = abuseReportRepository.insertIfNew(
                url.getId(), reason.name(), trimmed, visitorHasher.reporterHash(ip), LocalDateTime.now(clock));
        log.info("[REPORT] shortCode={}, reason={}, newReport={}", shortCode, reason, stored == 1);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AbuseReportSummaryResponse> list(AbuseReportStatusEnum status, Pageable pageable) {
        Page<LinkReportSummary> page = abuseReportRepository.findLinkSummaries(status, pageable);

        Map<Long, Map<AbuseReasonEnum, Long>> reasons = new HashMap<>();
        if (!page.isEmpty()) {
            List<Long> urlIds = page.getContent().stream().map(LinkReportSummary::urlId).toList();
            for (Object[] row : abuseReportRepository.countByReason(status, urlIds)) {
                reasons.computeIfAbsent((Long) row[0], k -> new EnumMap<>(AbuseReasonEnum.class))
                        .put((AbuseReasonEnum) row[1], (Long) row[2]);
            }
        }

        String baseUrl = trimmedBaseUrl();
        return PageResponse.of(page.map(s -> AbuseReportSummaryResponse.builder()
                .shortCode(s.shortCode())
                .shortUrl(baseUrl + "/" + s.shortCode())
                .originalUrl(s.originalUrl())
                .linkStatus(s.linkStatus())
                .reportCount(s.reportCount())
                .lastReportedAt(s.lastReportedAt())
                .reasons(reasons.getOrDefault(s.urlId(), Map.of()))
                .build()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AbuseReportDetailResponse> details(String shortCode, AbuseReportStatusEnum status) {
        Url url = find(shortCode);
        return abuseReportRepository.findTop100ByUrlIdAndStatusOrderByCreatedAtDesc(url.getId(), status).stream()
                .map(r -> AbuseReportDetailResponse.builder()
                        .id(r.getId())
                        .reason(r.getReason())
                        .details(r.getDetails())
                        .createdAt(r.getCreatedAt())
                        .build())
                .toList();
    }

    @Override
    @Transactional
    public int close(String shortCode, AbuseReportStatusEnum newStatus) {
        return closeForUrl(find(shortCode).getId(), newStatus);
    }

    @Override
    @Transactional
    public int move(String shortCode, AbuseReportStatusEnum from, AbuseReportStatusEnum to) {
        if (from == to) {
            throw new AppException(ErrorCode.VALIDATION_FAILED);
        }
        Url url = find(shortCode);
        String actor = auditorAware.getCurrentAuditor().orElse(AuditorAwareImpl.SYSTEM);
        int moved = abuseReportRepository.moveReports(url.getId(), from.name(), to.name(), LocalDateTime.now(clock), actor);
        if (moved > 0) {
            log.info("[ADMIN] Abuse reports moved: shortCode={}, {} -> {}, count={}", shortCode, from, to, moved);
        }
        return moved;
    }

    @Override
    @Transactional
    public int closeForUrl(long urlId, AbuseReportStatusEnum newStatus) {
        if (newStatus == AbuseReportStatusEnum.OPEN) {
            throw new IllegalArgumentException("a report cannot be closed as OPEN");
        }
        String actor = auditorAware.getCurrentAuditor().orElse(AuditorAwareImpl.SYSTEM);
        int closed = abuseReportRepository.closeOpenReports(urlId, newStatus.name(), LocalDateTime.now(clock), actor);
        if (closed > 0) {
            log.info("[ADMIN] Abuse reports closed: urlId={}, status={}, count={}", urlId, newStatus, closed);
        }
        return closed;
    }

    /** Link đã xoá coi như không tồn tại (mã không bao giờ được cấp lại). */
    private Url find(String shortCode) {
        return urlRepository.findByShortCode(shortCode)
                .filter(url -> url.getStatus() != UrlStatusEnum.DELETED)
                .orElseThrow(() -> new AppException(ErrorCode.URL_NOT_FOUND, shortCode));
    }

    private String trimmedBaseUrl() {
        String baseUrl = shortenerProperties.getBaseUrl();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
