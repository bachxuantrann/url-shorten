package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.OpenObserveProperties;
import com.auradev.url_shortener.dto.response.LogEntryResponse;
import com.auradev.url_shortener.dto.response.LogSummaryResponse;
import com.auradev.url_shortener.dto.response.MonitoringStatusResponse;
import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.service.MonitoringService;
import com.auradev.url_shortener.utils.OpenObserveClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Triển khai {@link MonitoringService}. Mỗi lần xem là hai hoặc ba câu truy vấn tới OpenObserve (số lượng cố định).
 *
 * <p>An toàn SQL: mức log chỉ lấy từ danh sách cho phép, tên stream phải khớp mẫu, từ khoá được thoát dấu nháy đơn và
 * dùng hàm {@code str_match_ignore_case} (so khớp chuỗi con, không phải mẫu LIKE) nên {@code %}, {@code _} là ký tự thường.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringServiceImpl implements MonitoringService {

    /** Khoảng thời gian được phép xem (phút). */
    static final Set<Integer> ALLOWED_MINUTES = Set.of(15, 60, 360, 1440);

    static final List<String> LEVELS = List.of("ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    static final int MAX_KEYWORD_LENGTH = 100;

    private static final Pattern STREAM_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    private static final int MAX_MESSAGE_CHARS = 4_000;
    private static final int MAX_STACKTRACE_CHARS = 8_000;

    private static final String COLUMNS = "_timestamp, severity, instrumentation_library_name, body, trace_id, "
            + "exception_type, exception_message, exception_stacktrace";

    private final OpenObserveProperties properties;
    private final OpenObserveClient client;
    private final Clock clock;

    @Override
    public MonitoringStatusResponse status() {
        boolean configured = client.isConfigured();
        return MonitoringStatusResponse.builder()
                .configured(configured)
                .reachable(configured && client.isReachable())
                .uiUrl(properties.getUiUrl() == null || properties.getUiUrl().isBlank() ? null : properties.getUiUrl().trim())
                .build();
    }

    @Override
    public LogSummaryResponse summary(int minutes) {
        requireMinutes(minutes);
        Instant end = clock.instant();
        Instant start = end.minus(Duration.ofMinutes(minutes));
        String stream = stream();

        Map<String, Long> levels = new LinkedHashMap<>();
        LEVELS.forEach(l -> levels.put(l, 0L));
        for (JsonNode row : client.searchLogs(
                "SELECT severity, count(*) AS n FROM \"" + stream + "\" GROUP BY severity", start, end, 0, LEVELS.size() * 2)) {
            String level = row.path("severity").asText("").toUpperCase();
            if (levels.containsKey(level)) {
                levels.merge(level, row.path("n").asLong(), Long::sum);
            }
        }

        List<LogSummaryResponse.ExceptionCount> top = new ArrayList<>();
        try {
            for (JsonNode row : client.searchLogs(
                    "SELECT exception_type, count(*) AS n FROM \"" + stream + "\" WHERE exception_type IS NOT NULL "
                            + "GROUP BY exception_type ORDER BY n DESC", start, end, 0, 5)) {
                top.add(new LogSummaryResponse.ExceptionCount(row.path("exception_type").asText(), row.path("n").asLong()));
            }
        } catch (AppException e) {
            // Stream chưa từng có exception thì chưa có cột exception_type và OpenObserve báo lỗi: coi như không có
            log.debug("[MONITORING] top exceptions unavailable: {}", e.getMessage());
        }
        return LogSummaryResponse.builder().minutes(minutes).levels(levels).topExceptions(top).build();
    }

    @Override
    public PageResponse<LogEntryResponse> logs(Set<String> levels, String keyword, int minutes, Pageable pageable) {
        requireMinutes(minutes);
        String where = where(levels, keyword);
        Instant end = clock.instant();
        Instant start = end.minus(Duration.ofMinutes(minutes));
        String stream = stream();

        JsonNode count = client.searchLogs("SELECT count(*) AS n FROM \"" + stream + "\" WHERE " + where, start, end, 0, 1);
        long total = count.isEmpty() ? 0 : count.get(0).path("n").asLong();

        List<LogEntryResponse> entries = new ArrayList<>();
        if (total > 0) {
            JsonNode hits = client.searchLogs(
                    "SELECT " + COLUMNS + " FROM \"" + stream + "\" WHERE " + where + " ORDER BY _timestamp DESC",
                    start, end, (int) pageable.getOffset(), pageable.getPageSize());
            hits.forEach(h -> entries.add(toEntry(h)));
        }
        return PageResponse.of(new PageImpl<>(entries, pageable, total));
    }

    // ---------- xây câu SQL an toàn ----------

    /** Điều kiện WHERE: mức log trong danh sách cho phép và (tuỳ chọn) từ khoá. */
    String where(Set<String> requested, String keyword) {
        Set<String> picked = new LinkedHashSet<>();
        for (String level : requested == null ? Set.<String>of() : requested) {
            String upper = level == null ? "" : level.trim().toUpperCase();
            if (!LEVELS.contains(upper)) {
                throw new AppException(ErrorCode.VALIDATION_FAILED);
            }
            picked.add(upper);
        }
        if (picked.isEmpty()) {
            picked.addAll(List.of("ERROR", "WARN"));
        }
        StringBuilder sql = new StringBuilder("severity IN (");
        sql.append(String.join(", ", picked.stream().map(l -> "'" + l + "'").toList())).append(")");
        if (keyword != null && !keyword.isBlank()) {
            String trimmed = keyword.trim();
            if (trimmed.length() > MAX_KEYWORD_LENGTH) {
                throw new AppException(ErrorCode.VALIDATION_FAILED);
            }
            sql.append(" AND str_match_ignore_case(body, '").append(trimmed.replace("'", "''")).append("')");
        }
        return sql.toString();
    }

    private String stream() {
        String stream = properties.getLogStream();
        if (stream == null || !STREAM_NAME.matcher(stream).matches()) {
            throw new AppException(ErrorCode.MONITORING_NOT_CONFIGURED);
        }
        return stream;
    }

    private static void requireMinutes(int minutes) {
        if (!ALLOWED_MINUTES.contains(minutes)) {
            throw new AppException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private LogEntryResponse toEntry(JsonNode hit) {
        long micros = hit.path("_timestamp").asLong();
        return LogEntryResponse.builder()
                .timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(micros / 1_000L), clock.getZone()))
                .level(hit.path("severity").asText(null))
                .logger(text(hit, "instrumentation_library_name", 300))
                .message(text(hit, "body", MAX_MESSAGE_CHARS))
                .traceId(text(hit, "trace_id", 64))
                .exceptionType(text(hit, "exception_type", 300))
                .exceptionMessage(text(hit, "exception_message", MAX_MESSAGE_CHARS))
                .exceptionStacktrace(text(hit, "exception_stacktrace", MAX_STACKTRACE_CHARS))
                .build();
    }

    /** Giá trị chuỗi của một trường (null nếu thiếu hoặc rỗng), cắt tới {@code max} ký tự. */
    private static String text(JsonNode node, String field, int max) {
        String value = node.path(field).asText(null);
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }
}
