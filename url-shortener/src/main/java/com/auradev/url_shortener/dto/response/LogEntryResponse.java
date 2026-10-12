package com.auradev.url_shortener.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Một dòng log lấy từ OpenObserve. Thời điểm theo múi giờ máy chủ như mọi API khác; chuỗi dài bị cắt để một dòng không
 * làm nặng cả trang.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogEntryResponse {

    private LocalDateTime timestamp;
    /** ERROR | WARN | INFO | DEBUG | TRACE */
    private String level;
    /** Tên logger đầy đủ (thường là tên lớp). */
    private String logger;
    private String message;
    /** Mã trace để tìm đúng lượt xử lý trong OpenObserve. */
    private String traceId;
    private String exceptionType;
    private String exceptionMessage;
    private String exceptionStacktrace;
}
