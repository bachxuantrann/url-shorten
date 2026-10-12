package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.dto.request.CreateUrlRequest;
import com.auradev.url_shortener.dto.request.UpdateUrlRequest;
import com.auradev.url_shortener.dto.response.AppealInfoResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.dto.response.UrlResponse;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.mapper.UrlMapper;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.service.LinkAppealService;
import com.auradev.url_shortener.service.UrlService;
import com.auradev.url_shortener.utils.LikePatterns;
import com.auradev.url_shortener.utils.QrCodeGenerator;
import com.auradev.url_shortener.utils.ShortCodeGenerator;
import com.auradev.url_shortener.utils.ShortCodes;
import com.auradev.url_shortener.utils.UrlCacheService;
import com.auradev.url_shortener.utils.UrlTargetPolicy;
import com.auradev.url_shortener.utils.UrlUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Triển khai {@link UrlService}.
 *
 * <p>Vòng đời trạng thái (do chủ link thao tác):
 * <pre>
 *   ACTIVE  ⇄ DISABLED          (disable / enable)
 *   ACTIVE  → EXPIRED           (job định kỳ khi quá expiresAt, xem UrlExpirationJob)
 *   EXPIRED → ACTIVE            (update với hạn mới)
 *   *       → DELETED           (delete, trừ BLOCKED)
 *   BLOCKED                     (chủ link không được sửa/tắt/xoá/đổi mật khẩu)
 * </pre>
 *
 * <p><b>Tạo link không dùng {@code @Transactional} ở mức method</b>: việc ghi nằm trong một
 * {@link TransactionTemplate} cho từng lần thử. Lý do: mã sinh tự động có thể trùng với một alias đã
 * được đăng ký (alias 7 ký tự {@code [0-9a-zA-Z]} có thể bằng một mã sinh ra sau này), và một lệnh
 * {@code INSERT} vi phạm ràng buộc UNIQUE làm Postgres huỷ cả transaction đang chạy. Vì vậy mỗi lần thử
 * có transaction riêng; khi trùng mã thì transaction đó rollback và vòng lặp bên ngoài thử lại với một
 * mã mới. Hash mật khẩu (BCrypt, vài trăm ms) cũng được tính ngoài transaction để không giữ kết nối DB.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrlServiceImpl implements UrlService {

    /** Số lần thử tối đa khi mã sinh tự động trùng một mã đã có (cực hiếm, mỗi lần dùng mã mới). */
    private static final int MAX_CODE_ATTEMPTS = 5;

    /** Tên ràng buộc UNIQUE của {@code urls.short_code} (xem V3). */
    private static final String SHORT_CODE_CONSTRAINT = "uq_urls_short_code";

    /** BCrypt chỉ xử lý 72 byte đầu; mật khẩu dài hơn sẽ bị cắt âm thầm nên từ chối ngay. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UrlRepository       urlRepository;
    private final UrlMapper           urlMapper;
    private final ShortCodeGenerator  shortCodeGenerator;
    private final ShortenerProperties shortenerProperties;
    private final UrlCacheService     urlCacheService;
    private final Clock               clock;
    private final UrlTargetPolicy     urlTargetPolicy;
    private final PasswordEncoder     passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final QrCodeGenerator     qrCodeGenerator;
    private final LinkAppealService   linkAppealService;

    // ===========================
    //  CREATE
    // ===========================

    @Override
    public UrlResponse create(UUID userId, CreateUrlRequest request) {
        assertWithinQuota(userId);
        return createLink(userId, request, request.getAlias(), request.getPassword());
    }

    @Override
    public UrlResponse createAnonymous(CreateUrlRequest request) {
        // Link ẩn danh không có alias hay mật khẩu, kể cả khi caller lỡ truyền vào
        return createLink(null, request, null, null);
    }

    private UrlResponse createLink(UUID userId, CreateUrlRequest request, String alias, String password) {
        String originalUrl = validateTarget(request.getOriginalUrl());
        String passwordHash = hashPassword(password);
        if (alias != null) {
            assertAliasAvailable(alias);
        }

        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            try {
                Url saved = transactionTemplate.execute(
                        status -> insertLink(userId, originalUrl, request.getExpiresAt(), alias, passwordHash));
                return toResponse(saved);
            } catch (DataIntegrityViolationException e) {
                if (!isShortCodeCollision(e)) {
                    throw e;
                }
                if (alias != null) {
                    // Ai đó vừa đăng ký cùng alias (race giữa kiểm tra trước và INSERT)
                    throw new AppException(ErrorCode.URL_ALIAS_UNAVAILABLE, alias);
                }
                log.warn("Generated short code collided with an existing code, retrying (attempt {}/{})",
                        attempt, MAX_CODE_ATTEMPTS);
            }
        }
        throw new IllegalStateException("Could not allocate a unique short code after " + MAX_CODE_ATTEMPTS + " attempts");
    }

    /** Một lần ghi, trong một transaction riêng (xem Javadoc của class). */
    private Url insertLink(UUID userId, String originalUrl, LocalDateTime expiresAt, String alias, String passwordHash) {
        String shortCode = alias != null ? alias : nextGeneratedCode();

        Url saved = urlRepository.save(Url.builder()
                .userId(userId)
                .shortCode(shortCode)
                .originalUrl(originalUrl)
                .status(UrlStatusEnum.ACTIVE)
                .expiresAt(expiresAt)
                .passwordHash(passwordHash)
                .build());

        // Xoá negative cache (nếu có ai đã truy cập mã này trước khi nó được tạo)
        urlCacheService.evictAfterCommit(saved.getShortCode());

        log.info("URL created: shortCode={}, userId={}, alias={}, passwordProtected={}",
                saved.getShortCode(), userId, alias != null, passwordHash != null);
        return saved;
    }

    /**
     * Số tuần tự duy nhất từ Postgres → mã duy nhất, an toàn khi chạy nhiều instance.
     * Bỏ qua (cực hiếm) mã trùng với từ khoá dành riêng như "swagger".
     */
    private String nextGeneratedCode() {
        String shortCode;
        do {
            shortCode = shortCodeGenerator.generate(urlRepository.nextShortCodeSequence());
        } while (ShortCodes.isReserved(shortCode));
        return shortCode;
    }

    /**
     * Alias phải đúng định dạng, không thuộc danh sách dành riêng và chưa được dùng (kể cả bởi link đã xoá,
     * vì mã không bao giờ được cấp lại). Lý do không được phân biệt trong thông báo.
     */
    private void assertAliasAvailable(String alias) {
        if (!ShortCodes.isValidFormat(alias)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED);
        }
        if (ShortCodes.isReserved(alias) || urlRepository.existsByShortCode(alias)) {
            throw new AppException(ErrorCode.URL_ALIAS_UNAVAILABLE, alias);
        }
    }

    private boolean isShortCodeCollision(DataIntegrityViolationException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains(SHORT_CODE_CONSTRAINT);
    }

    // ===========================
    //  READ
    // ===========================

    @Override
    @Transactional(readOnly = true)
    public UrlResponse get(UUID userId, String shortCode) {
        Url url = findOwned(userId, shortCode);
        UrlResponse response = toResponse(url);
        attachAppeals(List.of(url), List.of(response));
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UrlResponse> list(UUID userId, String keyword, UrlStatusEnum status, Pageable pageable) {
        String pattern = toLikePattern(keyword);
        Page<Url> page = urlRepository.findOwnedByUser(userId, status, pattern, pageable);
        Page<UrlResponse> responses = page.map(this::toResponse);
        attachAppeals(page.getContent(), responses.getContent());
        return PageResponse.of(responses);
    }

    /**
     * Gắn kháng nghị hiện tại vào các link bị chặn của một trang, bằng đúng một câu truy vấn cho cả trang (và không
     * truy vấn nào khi trang không có link bị chặn). {@code urls} và {@code responses} cùng thứ tự.
     */
    private void attachAppeals(List<Url> urls, List<UrlResponse> responses) {
        List<Url> blocked = urls.stream().filter(u -> u.getStatus() == UrlStatusEnum.BLOCKED).toList();
        if (blocked.isEmpty()) {
            return;
        }
        Map<Long, AppealInfoResponse> appeals = linkAppealService.currentFor(blocked);
        for (int i = 0; i < urls.size(); i++) {
            responses.get(i).setAppeal(appeals.get(urls.get(i).getId()));
        }
    }

    // ===========================
    //  UPDATE
    // ===========================

    @Override
    @Transactional
    public UrlResponse update(UUID userId, String shortCode, UpdateUrlRequest request) {
        Url url = findOwned(userId, shortCode);
        assertNotBlocked(url);

        url.setOriginalUrl(validateTarget(request.getOriginalUrl()));
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
                if (url.getExpiresAt() != null && !url.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
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
    //  PASSWORD
    // ===========================

    @Override
    public UrlResponse setPassword(UUID userId, String shortCode, String password) {
        // BCrypt tốn CPU: tính ngoài transaction, trước khi giữ kết nối DB
        String passwordHash = hashPassword(password);

        return transactionTemplate.execute(status -> {
            Url url = findOwned(userId, shortCode);
            assertNotBlocked(url);

            url.setPasswordHash(passwordHash);
            urlCacheService.evictAfterCommit(shortCode);

            log.info("URL password set: shortCode={}, userId={}", shortCode, userId);
            return toResponse(urlRepository.save(url));
        });
    }

    @Override
    @Transactional
    public UrlResponse removePassword(UUID userId, String shortCode) {
        Url url = findOwned(userId, shortCode);
        assertNotBlocked(url);

        url.setPasswordHash(null);
        urlCacheService.evictAfterCommit(shortCode);

        log.info("URL password removed: shortCode={}, userId={}", shortCode, userId);
        return toResponse(urlRepository.save(url));
    }

    // ===========================
    //  QR CODE
    // ===========================

    @Override
    @Transactional(readOnly = true)
    public byte[] qrCode(UUID userId, String shortCode, int size) {
        Url url = findOwned(userId, shortCode);
        return qrCodeGenerator.png(baseUrl() + "/" + url.getShortCode(), size);
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
        url.setDeletedAt(LocalDateTime.now(clock));
        urlRepository.save(url);
        urlCacheService.evictAfterCommit(shortCode);

        log.info("URL deleted: shortCode={}, userId={}", shortCode, userId);
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    /** Định dạng URL + chính sách an toàn (vòng lặp, địa chỉ nội bộ, blocklist). */
    private String validateTarget(String rawUrl) {
        String url = UrlUtils.validate(rawUrl);
        urlTargetPolicy.check(url);
        return url;
    }

    /**
     * BCrypt hash của mật khẩu link, hoặc null nếu không đặt mật khẩu.
     *
     * @throws AppException {@code INVALID_LINK_PASSWORD} nếu quá 72 byte khi mã hoá UTF-8
     */
    private String hashPassword(String password) {
        if (password == null) {
            return null;
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new AppException(ErrorCode.INVALID_LINK_PASSWORD);
        }
        return passwordEncoder.encode(password);
    }

    /**
     * Hạn mức số link (chưa xoá) mỗi user. Kiểm tra bằng một COUNT, không khoá: nhiều request đồng thời
     * của cùng một user có thể vượt hạn mức vài link, chấp nhận được vì đã có rate limit theo user.
     */
    private void assertWithinQuota(UUID userId) {
        int max = shortenerProperties.getMaxLinksPerUser();
        if (max > 0 && urlRepository.countByUserIdAndStatusNot(userId, UrlStatusEnum.DELETED) >= max) {
            throw new AppException(ErrorCode.URL_QUOTA_EXCEEDED, String.valueOf(max));
        }
    }

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
        return LikePatterns.contains(keyword);
    }

    /**
     * Link ACTIVE đã quá hạn nhưng job chưa kịp chuyển sang EXPIRED được báo là EXPIRED,
     * khớp với kết quả redirect (410) thay vì hiển thị ACTIVE.
     */
    private UrlResponse toResponse(Url url) {
        UrlResponse response = urlMapper.toUrlResponse(url, baseUrl());
        if (url.getStatus() == UrlStatusEnum.ACTIVE
                && url.getExpiresAt() != null
                && !url.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            response.setStatus(UrlStatusEnum.EXPIRED);
        }
        return response;
    }

    private String baseUrl() {
        String baseUrl = shortenerProperties.getBaseUrl();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
