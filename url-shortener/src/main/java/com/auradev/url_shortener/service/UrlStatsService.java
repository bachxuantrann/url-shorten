package com.auradev.url_shortener.service;

import com.auradev.url_shortener.dto.response.StatsOverviewResponse;
import com.auradev.url_shortener.dto.response.UrlStatsResponse;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Thống kê click của một link.
 */
public interface UrlStatsService {

    /** Số ngày mặc định khi không truyền khoảng (tính cả hôm nay). */
    int DEFAULT_DAYS = 30;

    /** Số ngày tối đa của một khoảng. */
    int MAX_DAYS = 366;

    /**
     * Thống kê link của chính người dùng. Link của người khác hoặc đã xoá trả {@code URL_NOT_FOUND}.
     *
     * @param from ngày bắt đầu (null = {@code to} lùi {@value #DEFAULT_DAYS} ngày)
     * @param to   ngày kết thúc, tính cả ngày này (null = hôm nay)
     * @throws com.auradev.url_shortener.exception.AppException {@code INVALID_STATS_RANGE} nếu
     *         {@code from} sau {@code to} hoặc khoảng dài hơn {@value #MAX_DAYS} ngày
     */
    UrlStatsResponse getOwnerStats(UUID userId, String shortCode, LocalDate from, LocalDate to);

    /** Thống kê link của bất kỳ ai (kể cả link ẩn danh); link đã xoá coi như không tồn tại. */
    UrlStatsResponse getAdminStats(String shortCode, LocalDate from, LocalDate to);

    /**
     * Tổng quan của mọi link chưa xoá của người dùng: số link theo trạng thái, tổng click theo ngày, link nhiều
     * click nhất và phân rã. Cùng quy tắc khoảng ngày như {@link #getOwnerStats}. Số câu lệnh SQL cố định (bốn),
     * không phụ thuộc số link.
     */
    StatsOverviewResponse getOverview(UUID userId, LocalDate from, LocalDate to);
}
