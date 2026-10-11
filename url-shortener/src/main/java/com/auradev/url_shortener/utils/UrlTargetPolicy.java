package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.repository.BlockedDomainRepository;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Chính sách an toàn cho URL đích, áp dụng <b>sau</b> {@link UrlUtils#validate} (đã đảm bảo
 * http/https, có host). Ba luật theo thứ tự:
 *
 * <ol>
 *   <li><b>Vòng lặp redirect:</b> host trùng host của chính dịch vụ → {@code UNSAFE_URL_TARGET}</li>
 *   <li><b>Địa chỉ nội bộ:</b> localhost, IP riêng/loopback/link-local, tên miền nội bộ
 *       (tắt được bằng {@code app.shortener.block-private-targets=false}) → {@code UNSAFE_URL_TARGET}</li>
 *   <li><b>Blocklist:</b> host hoặc domain cha nằm trong {@code blocked_domains} → {@code BLOCKED_DOMAIN}
 *       (đúng một query cho mọi domain ứng viên)</li>
 * </ol>
 *
 * <p>Xem {@link HostUtils} về giới hạn của kiểm tra chỉ dựa trên cú pháp (không phân giải DNS).
 */
@Component
public class UrlTargetPolicy {

    private final ShortenerProperties     properties;
    private final BlockedDomainRepository blockedDomainRepository;
    private final Set<String>             ownHosts;

    public UrlTargetPolicy(ShortenerProperties properties, BlockedDomainRepository blockedDomainRepository) {
        this.properties = properties;
        this.blockedDomainRepository = blockedDomainRepository;
        this.ownHosts = resolveOwnHosts(properties);
    }

    /**
     * @param url URL đã được {@link UrlUtils#validate} chấp nhận
     * @throws AppException {@code UNSAFE_URL_TARGET} hoặc {@code BLOCKED_DOMAIN}
     */
    public void check(String url) {
        String host = HostUtils.normalizeHost(URI.create(url).getHost());
        if (host == null || host.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_URL);
        }

        if (ownHosts.contains(host)) {
            throw new AppException(ErrorCode.UNSAFE_URL_TARGET);
        }
        if (properties.isBlockPrivateTargets() && HostUtils.isPrivateOrInternalHost(host)) {
            throw new AppException(ErrorCode.UNSAFE_URL_TARGET);
        }

        List<String> candidates = HostUtils.blocklistCandidates(host);
        if (candidates.isEmpty()) {
            return; // host một nhãn (chỉ xảy ra khi đã tắt chặn địa chỉ nội bộ): không có domain để tra
        }
        List<String> blocked = blockedDomainRepository.findBlockedDomainsIn(candidates);
        if (!blocked.isEmpty()) {
            throw new AppException(ErrorCode.BLOCKED_DOMAIN, blocked.get(0));
        }
    }

    private static Set<String> resolveOwnHosts(ShortenerProperties properties) {
        Set<String> hosts = new HashSet<>();
        String baseHost = hostOf(properties.getBaseUrl());
        if (baseHost != null) {
            hosts.add(baseHost);
        }
        for (String own : properties.getOwnHosts()) {
            String normalized = HostUtils.normalizeHost(own);
            if (normalized != null && !normalized.isEmpty()) {
                hosts.add(normalized);
            }
        }
        return Set.copyOf(hosts);
    }

    private static String hostOf(String url) {
        try {
            return HostUtils.normalizeHost(URI.create(url).getHost());
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
