package com.auradev.url_shortener.repository;

import com.auradev.url_shortener.entity.LinkAppeal;
import com.auradev.url_shortener.enums.AppealStatusEnum;
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
import java.util.UUID;

/**
 * Repository cho {@link LinkAppeal}. Mọi câu lệnh có số lượng cố định, không phụ thuộc số kháng nghị.
 */
@Repository
public interface LinkAppealRepository extends JpaRepository<LinkAppeal, Long> {

    /**
     * Ghi một kháng nghị mới nếu link chưa có kháng nghị đang mở. Trả về 1 nếu đã ghi, 0 nếu đã có một cái đang mở
     * (không lỗi, không làm hỏng transaction, kể cả khi hai request đến cùng lúc). Là câu lệnh native nên auditing
     * của JPA không chạy: các cột audit được set ở đây.
     */
    @Modifying
    @Query(value = """
            INSERT INTO link_appeals (url_id, user_id, message, status,
                                      created_at, updated_at, created_by, updated_by)
            VALUES (:urlId, :userId, :message, 'OPEN', :now, :now, :actor, :actor)
            ON CONFLICT (url_id) WHERE status = 'OPEN' DO NOTHING
            """, nativeQuery = true)
    int insertIfNoneOpen(
            @Param("urlId") long urlId,
            @Param("userId") UUID userId,
            @Param("message") String message,
            @Param("now") LocalDateTime now,
            @Param("actor") String actor
    );

    /**
     * Kháng nghị gần nhất của mỗi link trong một lô, bằng một câu truy vấn (không truy vấn theo từng link).
     */
    @Query("SELECT a FROM LinkAppeal a WHERE a.urlId IN :urlIds "
            + "AND a.id = (SELECT max(b.id) FROM LinkAppeal b WHERE b.urlId = a.urlId)")
    List<LinkAppeal> findLatestForUrls(@Param("urlIds") Collection<Long> urlIds);

    /** Kháng nghị cũ nhất trước (đang chờ lâu nhất được xem xét trước): dùng cho tab "Chờ xem xét". */
    @Query(value = "SELECT new com.auradev.url_shortener.repository.AppealRow("
            + "a.id, a.message, a.status, a.response, a.createdAt, a.updatedAt, "
            + "u.shortCode, u.originalUrl, u.status, u.blockedReason, o.username) "
            + "FROM LinkAppeal a, Url u, User o WHERE u.id = a.urlId AND o.id = a.userId AND a.status = :status "
            + "ORDER BY a.createdAt ASC, a.id ASC",
            countQuery = "SELECT count(a) FROM LinkAppeal a WHERE a.status = :status")
    Page<AppealRow> findRowsOldestFirst(@Param("status") AppealStatusEnum status, Pageable pageable);

    /** Kháng nghị xử lý gần nhất trước: dùng cho các tab đã xử lý. */
    @Query(value = "SELECT new com.auradev.url_shortener.repository.AppealRow("
            + "a.id, a.message, a.status, a.response, a.createdAt, a.updatedAt, "
            + "u.shortCode, u.originalUrl, u.status, u.blockedReason, o.username) "
            + "FROM LinkAppeal a, Url u, User o WHERE u.id = a.urlId AND o.id = a.userId AND a.status = :status "
            + "ORDER BY a.updatedAt DESC, a.id DESC",
            countQuery = "SELECT count(a) FROM LinkAppeal a WHERE a.status = :status")
    Page<AppealRow> findRowsNewestFirst(@Param("status") AppealStatusEnum status, Pageable pageable);

    /** Một dòng như trong danh sách, cho kết quả của accept/reject. */
    @Query("SELECT new com.auradev.url_shortener.repository.AppealRow("
            + "a.id, a.message, a.status, a.response, a.createdAt, a.updatedAt, "
            + "u.shortCode, u.originalUrl, u.status, u.blockedReason, o.username) "
            + "FROM LinkAppeal a, Url u, User o WHERE u.id = a.urlId AND o.id = a.userId AND a.id = :id")
    List<AppealRow> findRowById(@Param("id") long id);

    /**
     * Đóng một kháng nghị đang mở. Trả về 0 nếu nó không còn mở (admin khác đã xử lý), nên hai admin bấm cùng lúc
     * thì chỉ một người thành công. Bulk update nên {@code updated_at}/{@code updated_by} set thủ công.
     */
    @Modifying
    @Query(value = """
            UPDATE link_appeals
            SET status = :newStatus, response = :response, updated_at = :now, updated_by = :actor
            WHERE id = :id AND status = 'OPEN'
            """, nativeQuery = true)
    int closeOpenById(
            @Param("id") long id,
            @Param("newStatus") String newStatus,
            @Param("response") String response,
            @Param("now") LocalDateTime now,
            @Param("actor") String actor
    );

    /** Đóng kháng nghị đang mở của một link (tối đa một) khi admin gỡ chặn trực tiếp. */
    @Modifying
    @Query(value = """
            UPDATE link_appeals
            SET status = :newStatus, updated_at = :now, updated_by = :actor
            WHERE url_id = :urlId AND status = 'OPEN'
            """, nativeQuery = true)
    int closeOpenByUrl(
            @Param("urlId") long urlId,
            @Param("newStatus") String newStatus,
            @Param("now") LocalDateTime now,
            @Param("actor") String actor
    );
}
