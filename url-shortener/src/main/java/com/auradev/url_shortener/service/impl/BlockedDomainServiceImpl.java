package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.AuditorAwareImpl;
import com.auradev.url_shortener.dto.request.AddBlockedDomainRequest;
import com.auradev.url_shortener.dto.response.BlockedDomainResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.entity.BlockedDomain;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.mapper.BlockedDomainMapper;
import com.auradev.url_shortener.repository.BlockedDomainRepository;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.BlockedDomainService;
import com.auradev.url_shortener.utils.HostUtils;
import com.auradev.url_shortener.utils.UrlCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Triển khai {@link BlockedDomainService}.
 *
 * <p>Chặn hàng loạt link hiện có dùng một bulk {@code UPDATE ... RETURNING} (không N+1), nên
 * {@code updated_by} được set thủ công bằng tên admin đang thao tác.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlockedDomainServiceImpl implements BlockedDomainService {

    private final BlockedDomainRepository blockedDomainRepository;
    private final UrlRepository           urlRepository;
    private final BlockedDomainMapper     blockedDomainMapper;
    private final UrlCacheService         urlCacheService;
    private final AuditorAwareImpl        auditorAware;
    private final Clock                   clock;

    @Override
    @Transactional
    public BlockedDomainResponse add(AddBlockedDomainRequest request) {
        String domain = HostUtils.normalizeDomain(request.getDomain());
        if (blockedDomainRepository.existsByDomain(domain)) {
            throw new AppException(ErrorCode.BLOCKED_DOMAIN_EXISTS, domain);
        }

        String reason = StringUtils.hasText(request.getReason()) ? request.getReason().trim() : null;
        BlockedDomain saved;
        try {
            saved = blockedDomainRepository.saveAndFlush(BlockedDomain.builder().domain(domain).reason(reason).build());
        } catch (DataIntegrityViolationException e) {
            // Hai admin cùng thêm một domain đồng thời: ràng buộc UNIQUE chặn bản thứ hai
            throw new AppException(ErrorCode.BLOCKED_DOMAIN_EXISTS, domain);
        }

        int blockedLinks = 0;
        if (request.isBlockExistingLinks()) {
            List<String> codes = urlRepository.blockByHostPattern(
                    HostUtils.urlHostRegex(domain),
                    "Domain blocked: " + domain,
                    LocalDateTime.now(clock),
                    auditorAware.getCurrentAuditor().orElse(AuditorAwareImpl.SYSTEM));
            urlCacheService.evictAllAfterCommit(codes);
            blockedLinks = codes.size();
        }

        log.info("[ADMIN] Domain blocked: domain={}, existingLinksBlocked={}", domain, blockedLinks);

        BlockedDomainResponse response = blockedDomainMapper.toResponse(saved);
        response.setBlockedLinks(blockedLinks);
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<BlockedDomainResponse> list(String keyword, Pageable pageable) {
        Page<BlockedDomain> page = StringUtils.hasText(keyword)
                ? blockedDomainRepository.findByDomainContainingIgnoreCase(keyword.trim(), pageable)
                : blockedDomainRepository.findAll(pageable);
        return PageResponse.of(page.map(blockedDomainMapper::toResponse));
    }

    @Override
    @Transactional
    public void remove(Long id) {
        if (!blockedDomainRepository.existsById(id)) {
            throw new AppException(ErrorCode.BLOCKED_DOMAIN_NOT_FOUND, String.valueOf(id));
        }
        blockedDomainRepository.deleteById(id);
        log.info("[ADMIN] Domain unblocked: id={}", id);
    }
}
