package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.AuditorAwareImpl;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.UrlExpirationService;
import com.auradev.url_shortener.utils.UrlCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Triển khai {@link UrlExpirationService}.
 *
 * <p>Dùng một bulk {@code UPDATE ... RETURNING} cho cả batch nên số câu SQL không tăng theo số link
 * (không N+1, không N lần UPDATE). Auditing của JPA không áp dụng cho bulk update nên
 * {@code updated_by = system} và {@code updated_at} được set thủ công trong query. Cache của các link
 * được xoá sau khi commit, theo quy ước chung của mọi thay đổi trạng thái.
 */
@Service
@RequiredArgsConstructor
public class UrlExpirationServiceImpl implements UrlExpirationService {

    private final UrlRepository   urlRepository;
    private final UrlCacheService urlCacheService;

    @Override
    @Transactional
    public List<String> expireBatch(LocalDateTime now, int batchSize) {
        // 1 câu SQL cho cả batch, rồi 1 lệnh Redis DEL cho tất cả key cache
        List<String> expiredCodes = urlRepository.expireDueLinks(now, batchSize, AuditorAwareImpl.SYSTEM);
        urlCacheService.evictAllAfterCommit(expiredCodes);
        return expiredCodes;
    }
}
