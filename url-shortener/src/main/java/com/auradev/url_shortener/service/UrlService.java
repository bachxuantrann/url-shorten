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

    UrlResponse create(UUID userId, CreateUrlRequest request);

    UrlResponse get(UUID userId, String shortCode);

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
