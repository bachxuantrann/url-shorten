package com.auradev.url_shortener.repository;

import com.auradev.url_shortener.entity.Url;
import com.auradev.url_shortener.enums.UrlStatusEnum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository cho entity {@link Url}.
 */
@Repository
public interface UrlRepository extends JpaRepository<Url, Long> {

    /**
     * Lấy số kế tiếp của {@code short_code_seq}. Postgres đảm bảo mỗi lần gọi
     * trả về một số khác nhau, kể cả khi nhiều instance gọi đồng thời.
     */
    @Query(value = "SELECT nextval('short_code_seq')", nativeQuery = true)
    long nextShortCodeSequence();

    /** Tìm link theo mã công khai, mọi trạng thái (dùng cho redirect) */
    Optional<Url> findByShortCode(String shortCode);

    /** Tìm link theo mã và chủ sở hữu, bỏ qua link có trạng thái {@code excluded} (thường là DELETED) */
    Optional<Url> findByShortCodeAndUserIdAndStatusNot(String shortCode, UUID userId, UrlStatusEnum excluded);

    /**
     * Danh sách link của một user (không bao gồm link đã xoá), có thể lọc theo status
     * và tìm theo từ khoá trong URL đích hoặc short code.
     * Truyền {@code status = DELETED} sẽ không trả về gì vì link đã xoá luôn bị loại.
     *
     * @param pattern mẫu LIKE đã được hạ chữ thường và escape bằng ký tự {@code !}
     *                (dùng {@code "%"} để không lọc theo từ khoá)
     */
    @Query("SELECT u FROM Url u WHERE u.userId = :userId " +
            "AND u.status <> com.auradev.url_shortener.enums.UrlStatusEnum.DELETED " +
            "AND (:status IS NULL OR u.status = :status) " +
            "AND (LOWER(u.originalUrl) LIKE :pattern ESCAPE '!' " +
            "  OR LOWER(u.shortCode) LIKE :pattern ESCAPE '!')")
    Page<Url> findOwnedByUser(
            @Param("userId") UUID userId,
            @Param("status") UrlStatusEnum status,
            @Param("pattern") String pattern,
            Pageable pageable
    );
}
