package com.auradev.url_shortener.service;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Ghi nhận một lượt click thành công để đếm thống kê.
 *
 * <p>Hợp đồng: không bao giờ ném lỗi và không bao giờ chặn thread gọi, kể cả khi Kafka ngừng hoạt
 * động. Mất vài event còn tốt hơn làm hỏng hay làm chậm redirect.
 */
public interface ClickPublisher {

    /**
     * @param shortCode mã link vừa redirect/unlock thành công
     * @param request   request hiện tại (đọc IP, User-Agent, header quốc gia ngay trên thread gọi)
     */
    void publish(String shortCode, HttpServletRequest request);
}
