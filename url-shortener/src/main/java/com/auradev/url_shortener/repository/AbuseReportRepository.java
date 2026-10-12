package com.auradev.url_shortener.repository;

import com.auradev.url_shortener.entity.AbuseReport;
import com.auradev.url_shortener.enums.AbuseReportStatusEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Repository cho {@link AbuseReport}. Mọi câu lệnh có số lượng cố định, không phụ thuộc số báo cáo.
 */
@Repository
public interface AbuseReportRepository extends JpaRepository<AbuseReport, Long> {

    /**
     * Ghi một báo cáo mới nếu người này (cùng {@code reporterHash}) chưa báo link này. Trả về 1 nếu đã ghi,
     * 0 nếu đã có (không lỗi, không làm hỏng transaction). Là câu lệnh native nên auditing của JPA không chạy:
     * các cột audit được set ở đây (người báo không đăng nhập: {@code anonymous}).
     */
    @Modifying
    @Query(value = """
            INSERT INTO abuse_reports (url_id, reason, details, reporter_hash, status,
                                       created_at, updated_at, created_by, updated_by)
            VALUES (:urlId, :reason, :details, :reporterHash, 'OPEN', :now, :now, 'anonymous', 'anonymous')
            ON CONFLICT (url_id, reporter_hash) DO NOTHING
            """, nativeQuery = true)
    int insertIfNew(
            @Param("urlId") long urlId,
            @Param("reason") String reason,
            @Param("details") String details,
            @Param("reporterHash") String reporterHash,
            @Param("now") LocalDateTime now
    );

    /**
     * Mỗi link có báo cáo ở trạng thái {@code status} là một dòng, link báo gần nhất lên đầu. Một câu truy vấn
     * (cộng một câu đếm cho phân trang), không truy vấn thêm theo từng link.
     */
    @Query(value = "SELECT new com.auradev.url_shortener.repository.LinkReportSummary("
            + "u.id, u.shortCode, u.originalUrl, u.status, count(r), max(r.createdAt)) "
            + "FROM AbuseReport r, Url u WHERE u.id = r.urlId AND r.status = :status "
            + "GROUP BY u.id, u.shortCode, u.originalUrl, u.status "
            + "ORDER BY max(r.createdAt) DESC, u.id DESC",
            countQuery = "SELECT count(DISTINCT r.urlId) FROM AbuseReport r WHERE r.status = :status")
    Page<LinkReportSummary> findLinkSummaries(@Param("status") AbuseReportStatusEnum status, Pageable pageable);

    /** Số báo cáo theo (link, lý do) của một lô link, trong một câu truy vấn: các cột là urlId, reason, count. */
    @Query("SELECT r.urlId, r.reason, count(r) FROM AbuseReport r "
            + "WHERE r.status = :status AND r.urlId IN :urlIds GROUP BY r.urlId, r.reason")
    List<Object[]> countByReason(
            @Param("status") AbuseReportStatusEnum status,
            @Param("urlIds") Collection<Long> urlIds
    );

    /** Các báo cáo chi tiết của một link (mới nhất trước), tối đa 100. */
    List<AbuseReport> findTop100ByUrlIdAndStatusOrderByCreatedAtDesc(Long urlId, AbuseReportStatusEnum status);

    /**
     * Chuyển mọi báo cáo của một link đang ở trạng thái {@code fromStatus} sang {@code toStatus} bằng một câu lệnh và trả về
     * số báo cáo đã chuyển (0 nếu không còn báo cáo nào ở trạng thái đó, ví dụ admin khác vừa chuyển). Bulk update nên
     * {@code updated_at}/{@code updated_by} set thủ công.
     */
    @Modifying
    @Query(value = """
            UPDATE abuse_reports
            SET status = :toStatus, updated_at = :now, updated_by = :updatedBy
            WHERE url_id = :urlId AND status = :fromStatus
            """, nativeQuery = true)
    int moveReports(
            @Param("urlId") long urlId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("now") LocalDateTime now,
            @Param("updatedBy") String updatedBy
    );

    /**
     * Đóng mọi báo cáo đang mở của một link (RESOLVED hoặc DISMISSED) bằng một câu lệnh và trả về số báo cáo
     * đã đóng. Bulk update nên {@code updated_at}/{@code updated_by} set thủ công.
     */
    @Modifying
    @Query(value = """
            UPDATE abuse_reports
            SET status = :newStatus, updated_at = :now, updated_by = :updatedBy
            WHERE url_id = :urlId AND status = 'OPEN'
            """, nativeQuery = true)
    int closeOpenReports(
            @Param("urlId") long urlId,
            @Param("newStatus") String newStatus,
            @Param("now") LocalDateTime now,
            @Param("updatedBy") String updatedBy
    );
}
