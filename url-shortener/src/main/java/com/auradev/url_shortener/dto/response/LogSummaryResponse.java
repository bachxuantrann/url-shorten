package com.auradev.url_shortener.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Tóm tắt log trong một khoảng thời gian: số dòng theo mức và các loại exception gặp nhiều nhất.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogSummaryResponse {

    private int minutes;
    /** Số dòng log theo mức (ERROR, WARN, INFO, DEBUG, TRACE); mức không có dòng nào có giá trị 0. */
    private Map<String, Long> levels;
    private List<ExceptionCount> topExceptions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExceptionCount {
        private String type;
        private long count;
    }
}
