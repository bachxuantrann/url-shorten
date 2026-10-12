package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.config.OpenObserveProperties;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Máy khách tối giản gọi API tìm kiếm của OpenObserve ({@code POST /api/{org}/_search?type=logs}, xác thực Basic). Backend
 * dựng SQL từ giá trị đã kiểm tra (danh sách cho phép, chuỗi đã thoát nháy), không bao giờ nối thẳng chuỗi của người dùng.
 *
 * <p>Mọi lỗi mạng, hết giờ hay trả mã lỗi đều thành {@code MONITORING_UNAVAILABLE} (502), không làm lộ chi tiết nội bộ.
 */
@Slf4j
@Component
public class OpenObserveClient {

    private final OpenObserveProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public OpenObserveClient(OpenObserveProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build();
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** OpenObserve có đang trả lời không ({@code GET /healthz}, không cần xác thực). Không bao giờ ném lỗi. */
    public boolean isReachable() {
        if (!properties.isConfigured()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/healthz"))
                    .timeout(properties.getTimeout()).GET().build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException | RuntimeException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Chạy một câu SQL trên stream log trong khoảng thời gian cho trước và trả về mảng {@code hits}.
     *
     * @param sql câu SQL đã được dựng an toàn bởi nơi gọi
     */
    public JsonNode searchLogs(String sql, Instant start, Instant end, int from, int size) {
        if (!properties.isConfigured()) {
            throw new AppException(ErrorCode.MONITORING_NOT_CONFIGURED);
        }
        ObjectNode query = objectMapper.createObjectNode();
        query.put("sql", sql);
        query.put("start_time", micros(start));
        query.put("end_time", micros(end));
        query.put("from", from);
        query.put("size", size);
        ObjectNode body = objectMapper.createObjectNode();
        body.set("query", query);

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            baseUrl() + "/api/" + properties.getOrganization() + "/_search?type=logs"))
                    .timeout(properties.getTimeout())
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                            (properties.getEmail() + ":" + properties.getPassword()).getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[MONITORING] OpenObserve answered {} for a log search", response.statusCode());
                throw new AppException(ErrorCode.MONITORING_UNAVAILABLE);
            }
            JsonNode hits = objectMapper.readTree(response.body()).get("hits");
            return hits == null ? objectMapper.createArrayNode() : hits;
        } catch (IOException | RuntimeException e) {
            if (e instanceof AppException appException) {
                throw appException;
            }
            log.warn("[MONITORING] OpenObserve is not reachable: {}", e.toString());
            throw new AppException(ErrorCode.MONITORING_UNAVAILABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(ErrorCode.MONITORING_UNAVAILABLE);
        }
    }

    private String baseUrl() {
        String url = properties.getUrl().trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static long micros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }
}
