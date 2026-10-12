package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.AuditorAwareImpl;
import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.dto.response.AdminAppealResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.enums.AppealStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.repository.AppealRow;
import com.auradev.url_shortener.repository.LinkAppealRepository;
import com.auradev.url_shortener.service.AdminAppealService;
import com.auradev.url_shortener.service.AdminUrlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Triển khai {@link AdminAppealService}. Danh sách là một truy vấn (cộng một câu đếm) bất kể số kháng nghị, link và
 * chủ link nằm trong cùng câu truy vấn đó.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAppealServiceImpl implements AdminAppealService {

    private final LinkAppealRepository appealRepository;
    private final AdminUrlService      adminUrlService;
    private final AuditorAwareImpl     auditorAware;
    private final ShortenerProperties  shortenerProperties;
    private final Clock                clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminAppealResponse> list(AppealStatusEnum status, Pageable pageable) {
        Page<AppealRow> rows = status == AppealStatusEnum.OPEN
                ? appealRepository.findRowsOldestFirst(status, pageable)
                : appealRepository.findRowsNewestFirst(status, pageable);
        return PageResponse.of(rows.map(this::toResponse));
    }

    @Override
    @Transactional
    public AdminAppealResponse accept(long appealId) {
        AppealRow row = find(appealId);
        closeOpen(appealId, AppealStatusEnum.ACCEPTED, null);
        // Cùng transaction: nếu gỡ chặn lỗi thì kháng nghị cũng quay về đang chờ. unblockUrl xoá cache redirect.
        adminUrlService.unblockUrl(row.shortCode());
        log.info("[ADMIN] Appeal accepted: id={}, shortCode={}", appealId, row.shortCode());
        return toResponse(find(appealId));
    }

    @Override
    @Transactional
    public AdminAppealResponse reject(long appealId, String response) {
        AppealRow row = find(appealId);
        closeOpen(appealId, AppealStatusEnum.REJECTED, StringUtils.hasText(response) ? response.trim() : null);
        log.info("[ADMIN] Appeal rejected: id={}, shortCode={}", appealId, row.shortCode());
        return toResponse(find(appealId));
    }

    /** Đóng kháng nghị bằng một câu lệnh có điều kiện {@code status = 'OPEN'}: hai admin cùng bấm thì một người thắng. */
    private void closeOpen(long appealId, AppealStatusEnum newStatus, String response) {
        String actor = auditorAware.getCurrentAuditor().orElse(AuditorAwareImpl.SYSTEM);
        int closed = appealRepository.closeOpenById(appealId, newStatus.name(), response, LocalDateTime.now(clock), actor);
        if (closed == 0) {
            throw new AppException(ErrorCode.APPEAL_NOT_OPEN, appealId);
        }
    }

    private AppealRow find(long appealId) {
        return appealRepository.findRowById(appealId).stream().findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.APPEAL_NOT_FOUND, appealId));
    }

    private AdminAppealResponse toResponse(AppealRow r) {
        String baseUrl = shortenerProperties.getBaseUrl();
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return AdminAppealResponse.builder()
                .id(r.id())
                .shortCode(r.shortCode())
                .shortUrl(trimmed + "/" + r.shortCode())
                .originalUrl(r.originalUrl())
                .linkStatus(r.linkStatus())
                .blockedReason(r.blockedReason())
                .owner(r.ownerUsername())
                .message(r.message())
                .status(r.status())
                .response(r.response())
                .createdAt(r.createdAt())
                .updatedAt(r.updatedAt())
                .build();
    }
}
