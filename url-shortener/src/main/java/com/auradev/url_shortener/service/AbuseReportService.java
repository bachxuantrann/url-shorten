package com.auradev.url_shortener.service;

import com.auradev.url_shortener.dto.response.AbuseReportDetailResponse;
import com.auradev.url_shortener.dto.response.AbuseReportSummaryResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.enums.AbuseReasonEnum;
import com.auradev.url_shortener.enums.AbuseReportStatusEnum;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Báo cáo lạm dụng link: người lạ báo, admin xem xét.
 */
public interface AbuseReportService {

    /**
     * Ghi một báo cáo từ người ở địa chỉ {@code ip}. Cùng một người báo lại cùng một link thì bỏ qua (vẫn thành
     * công, để không lộ việc đã báo). Link đã bị chặn cũng bỏ qua (đã được xử lý). Link không tồn tại hoặc đã xoá
     * trả {@code URL_NOT_FOUND}.
     */
    void submit(String shortCode, AbuseReasonEnum reason, String details, String ip);

    /** Mỗi link có báo cáo ở trạng thái {@code status} là một dòng, báo gần nhất trước. */
    PageResponse<AbuseReportSummaryResponse> list(AbuseReportStatusEnum status, Pageable pageable);

    /** Các báo cáo cụ thể (mới nhất trước, tối đa 100) của một link ở trạng thái {@code status}. */
    List<AbuseReportDetailResponse> details(String shortCode, AbuseReportStatusEnum status);

    /**
     * Đóng mọi báo cáo đang mở của link bằng {@code RESOLVED} hoặc {@code DISMISSED}; trả về số báo cáo đã đóng
     * (0 nếu không còn báo cáo nào đang mở).
     */
    int close(String shortCode, AbuseReportStatusEnum newStatus);

    /**
     * Chuyển các báo cáo của link từ trạng thái {@code from} sang {@code to}, theo mọi chiều giữa OPEN / RESOLVED / DISMISSED.
     * Trả về số báo cáo đã chuyển (0 nếu không còn báo cáo nào ở {@code from}). {@code from == to} là yêu cầu sai
     * ({@code VALIDATION_FAILED}). Không đụng tới trạng thái của link: chặn / gỡ chặn là thao tác riêng.
     */
    int move(String shortCode, AbuseReportStatusEnum from, AbuseReportStatusEnum to);

    /** Như {@link #close} nhưng theo id link (dùng khi admin chặn link: báo cáo coi như đã xử lý). */
    int closeForUrl(long urlId, AbuseReportStatusEnum newStatus);
}
