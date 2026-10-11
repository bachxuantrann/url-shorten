package com.auradev.url_shortener.service;

import com.auradev.url_shortener.dto.request.CreateUrlRequest;
import com.auradev.url_shortener.dto.request.UpdateUrlRequest;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.dto.response.UrlResponse;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Nghiệp vụ quản lý short link của người dùng đã đăng nhập.
 *
 * <p>Mọi thao tác theo {@code shortCode} đều kiểm tra quyền sở hữu: link của người khác
 * hoặc đã xoá đều trả về {@code URL_NOT_FOUND} (không tiết lộ sự tồn tại của link).
 */
public interface UrlService {

    /** Tạo link cho user đã đăng nhập (kiểm tra hạn mức link của user). */
    UrlResponse create(UUID userId, CreateUrlRequest request);

    /**
     * Tạo link ẩn danh ({@code userId = null}): không ai sở hữu nên không thể sửa/xoá qua API người dùng,
     * chỉ admin chặn được. Được bảo vệ bằng rate limit theo IP ở tầng controller.
     */
    UrlResponse createAnonymous(CreateUrlRequest request);

    UrlResponse get(UUID userId, String shortCode);

    /**
     * Đặt hoặc đổi mật khẩu bảo vệ link. Người truy cập phải mở khoá bằng mật khẩu mới nhận được URL đích
     * (xem {@code RedirectService.unlockTarget}).
     */
    UrlResponse setPassword(UUID userId, String shortCode, String password);

    /** Gỡ mật khẩu: link redirect thẳng như bình thường. Idempotent. */
    UrlResponse removePassword(UUID userId, String shortCode);

    /**
     * Sinh mã QR (PNG) của short URL. Sinh tại chỗ, không lưu.
     *
     * @param size cạnh ảnh (pixel), trong khoảng cho phép của {@code QrCodeGenerator}
     */
    byte[] qrCode(UUID userId, String shortCode, int size);

    /**
     * Danh sách link của user (không gồm link đã xoá).
     *
     * @param keyword tìm theo URL đích hoặc short code, không phân biệt hoa thường, khớp một phần
     *                (null hoặc rỗng = không tìm kiếm). Ký tự {@code %} và {@code _} được hiểu theo nghĩa đen.
     * @param status  lọc theo trạng thái (null = tất cả)
     */
    PageResponse<UrlResponse> list(UUID userId, String keyword, UrlStatusEnum status, Pageable pageable);

    UrlResponse update(UUID userId, String shortCode, UpdateUrlRequest request);

    UrlResponse disable(UUID userId, String shortCode);

    UrlResponse enable(UUID userId, String shortCode);

    /** Xoá mềm. Mã đã cấp không bao giờ được tái sử dụng. */
    void delete(UUID userId, String shortCode);
}
