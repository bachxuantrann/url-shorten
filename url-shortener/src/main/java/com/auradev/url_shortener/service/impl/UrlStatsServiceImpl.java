package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.config.ShortenerProperties;
import com.auradev.url_shortener.dto.response.StatsOverviewResponse;
import com.auradev.url_shortener.dto.response.StatsOverviewResponse.LinkCounts;
import com.auradev.url_shortener.dto.response.StatsOverviewResponse.TopLink;
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
import com.auradev.url_shortener.repository.UrlStatsRepository.StatusCountRow;
import com.auradev.url_shortener.service.UrlStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongFunction;

/**
 * Triển khai {@link UrlStatsService}. Số câu lệnh SQL cố định, không phụ thuộc số ngày, số click hay số link:
 * <ul>
 *   <li>một link: ba câu (tìm link, đọc {@code url_daily_stats}, phân rã trên {@code url_clicks});</li>
 *   <li>tổng quan tài khoản: bốn câu (số link theo trạng thái, tổng theo ngày, link nhiều click nhất, phân rã).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UrlStatsServiceImpl implements UrlStatsService {

    /** Số phần tử tối đa của mỗi bảng phân rã (quốc gia, trình duyệt, hệ điều hành). */
    static final int TOP_N = 10;

    /** Số link nhiều click nhất trong tổng quan tài khoản. */
    static final int TOP_LINKS = 5;

    private static final String UNKNOWN = "Unknown";

    private final UrlRepository       urlRepository;
    private final UrlStatsRepository  statsRepository;
    private final ShortenerProperties shortenerProperties;
    private final Clock               clock;

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

    @Override
    @Transactional(readOnly = true)
    public StatsOverviewResponse getOverview(UUID userId, LocalDate fromParam, LocalDate toParam) {
        Range range = resolveRange(fromParam, toParam);

        List<StatusCountRow> statusRows = statsRepository.countLinksByStatus(userId, LocalDateTime.now(clock));
        Daily daily = fillDaily(statsRepository.findDailyForUser(userId, range.from(), range.to()), range);
        List<UrlStatsRepository.TopLinkRow> topRows =
                statsRepository.findTopLinksForUser(userId, range.from(), range.to(), TOP_LINKS);
        List<BreakdownRow> breakdowns = statsRepository.findBreakdownsForUser(
                userId, range.from().atStartOfDay(), range.to().plusDays(1).atStartOfDay());

        String baseUrl = trimmedBaseUrl();
        return StatsOverviewResponse.builder()
                .from(range.from())
                .to(range.to())
                .links(linkCounts(statusRows))
                .totals(daily.totals())
                .daily(daily.days())
                .topLinks(topRows.stream()
                        .map(r -> TopLink.builder()
                                .shortCode(r.shortCode())
                                .shortUrl(baseUrl + "/" + r.shortCode())
                                .originalUrl(r.originalUrl())
                                .clicks(r.clicks())
                                .build())
                        .toList())
                .countries(top(breakdowns, "country", BreakdownRow::humanClicks))
                .devices(top(breakdowns, "device", BreakdownRow::clicks))
                .browsers(top(breakdowns, "browser", BreakdownRow::humanClicks))
                .operatingSystems(top(breakdowns, "os", BreakdownRow::humanClicks))
                .build();
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    private UrlStatsResponse build(Url url, LocalDate fromParam, LocalDate toParam) {
        Range range = resolveRange(fromParam, toParam);
        Daily daily = fillDaily(statsRepository.findDaily(url.getId(), range.from(), range.to()), range);

        List<BreakdownRow> breakdowns = statsRepository.findBreakdowns(
                url.getId(), range.from().atStartOfDay(), range.to().plusDays(1).atStartOfDay());

        return UrlStatsResponse.builder()
                .shortCode(url.getShortCode())
                .from(range.from())
                .to(range.to())
                .totals(daily.totals())
                .daily(daily.days())
                .countries(top(breakdowns, "country", BreakdownRow::humanClicks))
                .devices(top(breakdowns, "device", BreakdownRow::clicks))
                .browsers(top(breakdowns, "browser", BreakdownRow::humanClicks))
                .operatingSystems(top(breakdowns, "os", BreakdownRow::humanClicks))
                .build();
    }

    private record Range(LocalDate from, LocalDate to) {
    }

    private record Daily(List<DailyStat> days, Totals totals) {
    }

    /** Giá trị mặc định (30 ngày gần nhất) và kiểm tra khoảng: không đảo ngược, tối đa {@value #MAX_DAYS} ngày. */
    private Range resolveRange(LocalDate fromParam, LocalDate toParam) {
        LocalDate to = toParam != null ? toParam : LocalDate.now(clock);
        LocalDate from = fromParam != null ? fromParam : to.minusDays(DEFAULT_DAYS - 1L);
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new AppException(ErrorCode.INVALID_STATS_RANGE, MAX_DAYS);
        }
        return new Range(from, to);
    }

    /** Điền 0 cho ngày không có click để client vẽ biểu đồ liên tục, và cộng tổng của cả khoảng. */
    private Daily fillDaily(List<DailyRow> rows, Range range) {
        Map<LocalDate, DailyRow> byDate = new HashMap<>();
        for (DailyRow row : rows) {
            byDate.put(row.date(), row);
        }

        List<DailyStat> days = new ArrayList<>();
        long clicks = 0;
        long botClicks = 0;
        long uniqueVisitors = 0;
        for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
            DailyRow row = byDate.get(day);
            long dayClicks = row == null ? 0 : row.clicks();
            long dayBots = row == null ? 0 : row.botClicks();
            long dayVisitors = row == null ? 0 : row.uniqueVisitors();
            days.add(new DailyStat(day, dayClicks, dayBots, dayVisitors));
            clicks += dayClicks;
            botClicks += dayBots;
            uniqueVisitors += dayVisitors;
        }
        return new Daily(days, new Totals(clicks, botClicks, uniqueVisitors));
    }

    private LinkCounts linkCounts(List<StatusCountRow> rows) {
        Map<String, Long> byStatus = new HashMap<>();
        for (StatusCountRow row : rows) {
            byStatus.put(row.status(), row.count());
        }
        long active = byStatus.getOrDefault(UrlStatusEnum.ACTIVE.name(), 0L);
        long disabled = byStatus.getOrDefault(UrlStatusEnum.DISABLED.name(), 0L);
        long expired = byStatus.getOrDefault(UrlStatusEnum.EXPIRED.name(), 0L);
        long blocked = byStatus.getOrDefault(UrlStatusEnum.BLOCKED.name(), 0L);
        return LinkCounts.builder()
                .total(active + disabled + expired + blocked)
                .active(active)
                .disabled(disabled)
                .expired(expired)
                .blocked(blocked)
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

    private String trimmedBaseUrl() {
        String baseUrl = shortenerProperties.getBaseUrl();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
