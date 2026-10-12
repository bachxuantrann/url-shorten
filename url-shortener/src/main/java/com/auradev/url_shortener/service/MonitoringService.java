package com.auradev.url_shortener.service;

import com.auradev.url_shortener.dto.response.LogEntryResponse;
import com.auradev.url_shortener.dto.response.LogSummaryResponse;
import com.auradev.url_shortener.dto.response.MonitoringStatusResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import org.springframework.data.domain.Pageable;

import java.util.Set;

/**
 * Xem log của hệ thống qua OpenObserve (chỉ admin). Backend giữ thông tin đăng nhập, trình duyệt không bao giờ gọi
 * OpenObserve trực tiếp.
 */
public interface MonitoringService {

    /** Trạng thái kết nối; không bao giờ ném lỗi. */
    MonitoringStatusResponse status();

    /**
     * Số dòng log theo mức và top exception trong {@code minutes} phút gần nhất ({@code 15 | 60 | 360 | 1440}, ngoài
     * danh sách là {@code VALIDATION_FAILED}). Chưa cấu hình → {@code MONITORING_NOT_CONFIGURED}; OpenObserve lỗi →
     * {@code MONITORING_UNAVAILABLE}.
     */
    LogSummaryResponse summary(int minutes);

    /**
     * Các dòng log mới nhất trước.
     *
     * @param levels  các mức cần lấy, chỉ nhận ERROR/WARN/INFO/DEBUG/TRACE (rỗng = ERROR và WARN)
     * @param keyword chứa chuỗi này (không phân biệt hoa thường), tối đa 100 ký tự; null hoặc rỗng = không lọc
     */
    PageResponse<LogEntryResponse> logs(Set<String> levels, String keyword, int minutes, Pageable pageable);
}
