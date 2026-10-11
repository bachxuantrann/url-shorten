package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.dto.response.UrlStatsResponse;
import com.auradev.url_shortener.dto.response.UrlStatsResponse.BreakdownItem;
import com.auradev.url_shortener.dto.response.UrlStatsResponse.DailyStat;
import com.auradev.url_shortener.dto.response.UrlStatsResponse.Totals;
import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;
import com.auradev.url_shortener.repository.UrlRepository;
import com.auradev.url_shortener.repository.UrlStatsRepository;
import com.auradev.url_shortener.repository.UrlStatsRepository.BreakdownRow;
import com.auradev.url_shortener.repository.UrlStatsRepository.DailyRow;
import com.auradev.url_shortener.service.UrlStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * Triển khai {@link UrlStatsService}. Mỗi lần xem là ba câu lệnh cố định: một để tìm link, một đọc
 * {@code url_daily_stats}, một phân rã trên {@code url_clicks}; không phụ thuộc số ngày hay số click.
 */
@Service
@RequiredArgsConstructor
public class UrlStatsServiceImpl implements UrlStatsService {

    /** Số phần tử tối đa của mỗi bảng phân rã (quốc gia, trình duyệt, hệ điều hành). */
    static final int TOP_N = 10;

    private static final String UNKNOWN = "Unknown";

    private final UrlRepository      urlRepository;
    private final UrlStatsRepository statsRepository;
    private final Clock              clock;

    @Override
    @Transactional(readOnly = true)
    public UrlStatsResponse getOwnerStats(UUID userId, String shortCode, LocalDate from, LocalDate to) {
        Url url = urlRepository.findByShortCodeAndUserIdAndStatusNot(shortCode, userId, UrlStatusEnum.DELETED)
                .orElseThrow(() -> new AppException(ErrorCode.URL_NOT_FOUND, shortCode));
        return build(url, from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public UrlStatsResponse getAdminStats(String shortCode, LocalDate from, LocalDate to) {
        Url url = urlRepository.findByShortCode(shortCode)
                .filter(found -> found.getStatus() != UrlStatusEnum.DELETED)
                .orElseThrow(() -> new AppException(ErrorCode.URL_NOT_FOUND, shortCode));
        return build(url, from, to);
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    private UrlStatsResponse build(Url url, LocalDate fromParam, LocalDate toParam) {
        LocalDate to = toParam != null ? toParam : LocalDate.now(clock);
        LocalDate from = fromParam != null ? fromParam : to.minusDays(DEFAULT_DAYS - 1L);
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new AppException(ErrorCode.INVALID_STATS_RANGE, MAX_DAYS);
        }

        Map<LocalDate, DailyRow> byDate = new HashMap<>();
        for (DailyRow row : statsRepository.findDaily(url.getId(), from, to)) {
            byDate.put(row.date(), row);
        }

        // Điền 0 cho ngày không có click để client vẽ biểu đồ liên tục
        List<DailyStat> daily = new ArrayList<>();
        long clicks = 0;
        long botClicks = 0;
        long uniqueVisitors = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            DailyRow row = byDate.get(day);
            long dayClicks = row == null ? 0 : row.clicks();
            long dayBots = row == null ? 0 : row.botClicks();
            long dayVisitors = row == null ? 0 : row.uniqueVisitors();
            daily.add(new DailyStat(day, dayClicks, dayBots, dayVisitors));
            clicks += dayClicks;
            botClicks += dayBots;
            uniqueVisitors += dayVisitors;
        }

        List<BreakdownRow> breakdowns = statsRepository.findBreakdowns(
                url.getId(), from.atStartOfDay(), to.plusDays(1).atStartOfDay());

        return UrlStatsResponse.builder()
                .shortCode(url.getShortCode())
                .from(from)
                .to(to)
                .totals(new Totals(clicks, botClicks, uniqueVisitors))
                .daily(daily)
                .countries(top(breakdowns, "country", BreakdownRow::humanClicks))
                .devices(top(breakdowns, "device", BreakdownRow::clicks))
                .browsers(top(breakdowns, "browser", BreakdownRow::humanClicks))
                .operatingSystems(top(breakdowns, "os", BreakdownRow::humanClicks))
                .build();
    }

    /**
     * Lấy các dòng của một chiều, bỏ dòng không có click (ví dụ chiều chỉ tính khách thật mà toàn bot),
     * sắp theo số click giảm dần rồi theo tên, giữ {@link #TOP_N} dòng đầu.
     */
    private List<BreakdownItem> top(List<BreakdownRow> rows, String dimension, ToLongFunction<BreakdownRow> count) {
        return rows.stream()
                .filter(row -> row.dimension().equals(dimension))
                .map(row -> new BreakdownItem(row.name() == null ? UNKNOWN : row.name(), count.applyAsLong(row)))
                .filter(item -> item.getClicks() > 0)
                .sorted(Comparator.comparingLong(BreakdownItem::getClicks).reversed()
                        .thenComparing(BreakdownItem::getName))
                .limit(TOP_N)
                .toList();
    }
}
