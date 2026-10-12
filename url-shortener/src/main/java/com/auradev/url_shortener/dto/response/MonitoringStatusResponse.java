package com.auradev.url_shortener.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Trạng thái kết nối tới OpenObserve cho trang "Giám sát". Không bao giờ chứa thông tin đăng nhập.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitoringStatusResponse {

    /** Backend đã được cấu hình địa chỉ và tài khoản đọc OpenObserve chưa. */
    private boolean configured;
    /** OpenObserve có đang trả lời không (luôn false khi chưa cấu hình). */
    private boolean reachable;
    /** Địa chỉ giao diện OpenObserve cho trình duyệt (nút "Mở OpenObserve"), có thể trống. */
    private String uiUrl;
}
