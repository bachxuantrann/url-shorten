package com.auradev.url_shortener.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Lớp cha cho các entity cần audit.
 *
 * <p>{@code createdAt} và {@code updatedAt} được Hibernate tự động set.
 * {@code createdBy} và {@code updatedBy} được Spring Data auditing set qua
 * {@link com.auradev.url_shortener.config.AuditorAwareImpl} (username của người thực hiện).
 * Entity con không cần khai báo lại.
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    /** Thời điểm tạo — tự động set, không cho phép sửa */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Thời điểm cập nhật gần nhất — tự động cập nhật */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Username người tạo — {@code system} / {@code anonymous} nếu không có người dùng đăng nhập */
    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false, length = 50)
    private String createdBy;

    /** Username người cập nhật gần nhất (khi tạo mới bằng người tạo) */
    @LastModifiedBy
    @Column(name = "updated_by", nullable = false, length = 50)
    private String updatedBy;
}
