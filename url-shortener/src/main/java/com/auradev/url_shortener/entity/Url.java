package com.auradev.url_shortener.entity;

import com.auradev.url_shortener.enums.UrlStatusEnum;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity đại diện cho một short link.
 *
 * <p>Thiết kế:
 * <ul>
 *   <li>{@code id} là khoá chính nội bộ, {@code shortCode} là định danh công khai</li>
 *   <li>{@code userId} nullable — link ẩn danh (không thuộc user nào)</li>
 *   <li>Xoá mềm: {@code status = DELETED} + {@code deletedAt}, mã không bao giờ được cấp lại</li>
 *   <li>{@code expiresAt} (thời điểm hết hạn) tách biệt với {@code status} (trạng thái nghiệp vụ)</li>
 * </ul>
 */
@Entity
@Table(name = "urls")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Url extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    /** Chủ sở hữu — null nếu là link ẩn danh */
    @Column(name = "user_id", columnDefinition = "uuid")
    private UUID userId;

    /** Mã rút gọn công khai — unique, không đổi sau khi tạo */
    @Column(name = "short_code", nullable = false, unique = true, updatable = false, length = 16)
    private String shortCode;

    @Column(name = "original_url", nullable = false, columnDefinition = "text")
    private String originalUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private UrlStatusEnum status = UrlStatusEnum.ACTIVE;

    /** Thời điểm hết hạn — null nghĩa là không bao giờ hết hạn */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** Tổng số click — được cập nhật bất đồng bộ ở các phase sau */
    @Column(name = "click_count", nullable = false)
    @Builder.Default
    private long clickCount = 0L;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
