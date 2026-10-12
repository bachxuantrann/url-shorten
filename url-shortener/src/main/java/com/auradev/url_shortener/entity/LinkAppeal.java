package com.auradev.url_shortener.entity;

import com.auradev.url_shortener.enums.AppealStatusEnum;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * Kháng nghị của chủ link đối với việc link bị chặn. Ghi bằng {@code INSERT ... ON CONFLICT DO NOTHING}
 * ({@code LinkAppealRepository#insertIfNoneOpen}) và đóng bằng câu lệnh bulk, entity này dùng để đọc.
 *
 * <p>Tham chiếu link và chủ link bằng id (không dùng quan hệ JPA) để danh sách không kéo theo truy vấn thừa
 * theo từng dòng.
 */
@Entity
@Table(name = "link_appeals")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LinkAppeal extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "url_id", nullable = false, updatable = false)
    private Long urlId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "message", nullable = false, updatable = false, length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AppealStatusEnum status;

    /** Lời phản hồi của admin (tuỳ chọn). */
    @Column(name = "response", length = 255)
    private String response;
}
