package com.auradev.url_shortener.entity;

import com.auradev.url_shortener.enums.AbuseReasonEnum;
import com.auradev.url_shortener.enums.AbuseReportStatusEnum;
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

/**
 * Một báo cáo lạm dụng về một link. Ghi bằng câu lệnh {@code INSERT ... ON CONFLICT DO NOTHING}
 * ({@code AbuseReportRepository#insertIfNew}) chứ không qua entity, để cùng một người báo hai lần không làm
 * hỏng transaction; entity này dùng để đọc.
 *
 * <p>Tham chiếu link bằng {@code urlId} (không dùng quan hệ JPA) để danh sách báo cáo không kéo theo
 * truy vấn thừa theo từng dòng.
 */
@Entity
@Table(name = "abuse_reports")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class AbuseReport extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "url_id", nullable = false, updatable = false)
    private Long urlId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 20)
    private AbuseReasonEnum reason;

    @Column(name = "details", updatable = false, length = 500)
    private String details;

    /** HMAC của IP người báo (không phải IP thô). */
    @Column(name = "reporter_hash", nullable = false, updatable = false, length = 32)
    private String reporterHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AbuseReportStatusEnum status;
}
