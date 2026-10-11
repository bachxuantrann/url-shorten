package com.auradev.url_shortener.service.impl;

import com.auradev.url_shortener.service.ClickPartitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Triển khai {@link ClickPartitionService}.
 *
 * <p>Tên phân vùng {@code url_clicks_yYYYYmMM}. Tên đưa vào DDL luôn được dựng từ {@link YearMonth}
 * hoặc đã khớp regex chặt, không bao giờ lấy nguyên văn từ dữ liệu ngoài.
 *
 * <p>Phân vùng DEFAULT hứng click nằm ngoài mọi tháng đã tạo. Nếu DEFAULT đang chứa dòng của một tháng
 * thì Postgres từ chối tạo phân vùng tháng đó (lỗi được log, các tháng khác vẫn tiếp tục); tạo trước
 * vài tháng để chuyện này không xảy ra.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickPartitionServiceImpl implements ClickPartitionService {

    /** Khoá advisory của job bảo trì phân vùng (số tuỳ ý nhưng cố định giữa các instance). */
    private static final long ADVISORY_LOCK_KEY = 7_066_001L;

    private static final Pattern PARTITION_NAME = Pattern.compile("^url_clicks_y(\\d{4})m(\\d{2})$");

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public Result maintain(LocalDate today, int monthsAhead, int retentionMonths) {
        Boolean locked = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        if (!Boolean.TRUE.equals(locked)) {
            return new Result(false, List.of(), List.of());
        }

        YearMonth current = YearMonth.from(today);
        return new Result(true, createUpcoming(current, monthsAhead), dropExpired(current, retentionMonths));
    }

    private List<String> createUpcoming(YearMonth current, int monthsAhead) {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < Math.max(1, monthsAhead); i++) {
            YearMonth month = current.plusMonths(i);
            String name = partitionName(month);
            if (exists(name)) {
                continue;
            }
            try {
                // Savepoint: một tháng lỗi không làm hỏng transaction của các tháng còn lại
                jdbcTemplate.execute("SAVEPOINT create_partition");
                jdbcTemplate.execute("CREATE TABLE " + name + " PARTITION OF url_clicks FOR VALUES FROM ('"
                        + month.atDay(1) + "') TO ('" + month.plusMonths(1).atDay(1) + "')");
                jdbcTemplate.execute("RELEASE SAVEPOINT create_partition");
                created.add(name);
            } catch (RuntimeException e) {
                jdbcTemplate.execute("ROLLBACK TO SAVEPOINT create_partition");
                log.error("Could not create click partition {} (rows already in the default partition?)", name, e);
            }
        }
        return created;
    }

    private List<String> dropExpired(YearMonth current, int retentionMonths) {
        if (retentionMonths <= 0) {
            return List.of();
        }
        YearMonth oldestKept = current.minusMonths(retentionMonths);

        List<String> names = jdbcTemplate.queryForList("""
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'url_clicks'::regclass
                """, String.class);

        List<String> dropped = new ArrayList<>();
        for (String name : names) {
            Matcher matcher = PARTITION_NAME.matcher(name);
            if (!matcher.matches()) {
                continue; // url_clicks_default và mọi thứ lạ đều được giữ
            }
            YearMonth month = YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            if (month.isBefore(oldestKept)) {
                jdbcTemplate.execute("DROP TABLE " + name);
                dropped.add(name);
            }
        }
        return dropped;
    }

    private boolean exists(String name) {
        Boolean found = jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name);
        return Boolean.TRUE.equals(found);
    }

    static String partitionName(YearMonth month) {
        return String.format("url_clicks_y%04dm%02d", month.getYear(), month.getMonthValue());
    }
}
