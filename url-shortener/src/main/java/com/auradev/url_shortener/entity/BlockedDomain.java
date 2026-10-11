package com.auradev.url_shortener.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Domain bị cấm làm URL đích. Khớp cả subdomain: chặn {@code example.com}
 * cũng chặn {@code evil.example.com}.
 *
 * <p>{@code domain} luôn được lưu dạng chữ thường, không có {@code www.}/dấu chấm thừa
 * (chuẩn hoá bởi {@code HostUtils.normalizeDomain}).
 */
@Entity
@Table(name = "blocked_domains")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BlockedDomain extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "domain", nullable = false, unique = true, updatable = false, length = 253)
    private String domain;

    @Column(name = "reason", length = 255)
    private String reason;
}
