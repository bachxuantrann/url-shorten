package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.AuditorAwareImpl;
import com.auradev.url_shortener.dto.response.AppealInfoResponse;
import com.auradev.url_shortener.entity.LinkAppeal;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.AppealStatusEnum;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.repository.LinkAppealRepository;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.LinkAppealService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Triển khai {@link LinkAppealService}. Số câu lệnh SQL không phụ thuộc số kháng nghị: gửi là một truy vấn link và
 * một {@code INSERT ... ON CONFLICT}, tra cả trang link là một câu {@code IN (...)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkAppealServiceImpl implements LinkAppealService {

    private final LinkAppealRepository appealRepository;
    private final UrlRepository        urlRepository;
    private final AuditorAwareImpl     auditorAware;
    private final Clock                clock;

    @Override
    @Transactional
    public void submit(UUID userId, String shortCode, String message) {
        Url url = urlRepository
                .findByShortCodeAndUserIdAndStatusNot(shortCode, userId, UrlStatusEnum.DELETED)
                .orElseThrow(() -> new AppException(ErrorCode.URL_NOT_FOUND, shortCode));
        if (url.getStatus() != UrlStatusEnum.BLOCKED) {
            throw new AppException(ErrorCode.URL_INVALID_STATE, url.getStatus());
        }

        String actor = auditorAware.getCurrentAuditor().orElse(AuditorAwareImpl.SYSTEM);
        int stored = appealRepository.insertIfNoneOpen(
                url.getId(), userId, message.trim(), LocalDateTime.now(clock), actor);
        if (stored == 0) {
            throw new AppException(ErrorCode.APPEAL_ALREADY_OPEN);
        }
        log.info("[APPEAL] Submitted: shortCode={}, userId={}", shortCode, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, AppealInfoResponse> currentFor(Collection<Url> blockedUrls) {
        if (blockedUrls.isEmpty()) {
            return Map.of();
        }
        Map<Long, Url> byId = new HashMap<>();
        blockedUrls.forEach(u -> byId.put(u.getId(), u));

        Map<Long, AppealInfoResponse> result = new HashMap<>();
        for (LinkAppeal appeal : appealRepository.findLatestForUrls(byId.keySet())) {
            if (isRelevant(appeal, byId.get(appeal.getUrlId()))) {
                result.put(appeal.getUrlId(), AppealInfoResponse.builder()
                        .status(appeal.getStatus())
                        .message(appeal.getMessage())
                        .response(appeal.getResponse())
                        .createdAt(appeal.getCreatedAt())
                        .build());
            }
        }
        return result;
    }

    /**
     * Kháng nghị đang chờ luôn liên quan. Kháng nghị đã từ chối chỉ liên quan khi gửi SAU lần chặn hiện tại: nếu
     * admin chặn lại link (lúc đó {@code updatedAt} của link mới hơn) thì lời từ chối cũ không còn đúng nữa. Kháng
     * nghị đã chấp nhận thì link đã được gỡ chặn, không liên quan.
     */
    private boolean isRelevant(LinkAppeal appeal, Url url) {
        return switch (appeal.getStatus()) {
            case OPEN -> true;
            case REJECTED -> !appeal.getCreatedAt().isBefore(url.getUpdatedAt());
            case ACCEPTED -> false;
        };
    }

    @Override
    @Transactional
    public int closeForUrl(long urlId, AppealStatusEnum newStatus) {
        if (newStatus == AppealStatusEnum.OPEN) {
            throw new IllegalArgumentException("an appeal cannot be closed as OPEN");
        }
        String actor = auditorAware.getCurrentAuditor().orElse(AuditorAwareImpl.SYSTEM);
        return appealRepository.closeOpenByUrl(urlId, newStatus.name(), LocalDateTime.now(clock), actor);
    }
}
