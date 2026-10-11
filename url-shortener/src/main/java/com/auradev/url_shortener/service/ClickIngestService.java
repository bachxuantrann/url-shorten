package com.auradev.url_shortener.service;

import com.auradev.url_shortener.event.ClickEvent;

import java.util.List;

/**
 * Ghi một lô {@link ClickEvent} vào cơ sở dữ liệu. Idempotent: event giao lại không tạo thêm dòng
 * và không làm tăng {@code click_count}.
 */
public interface ClickIngestService {

    /**
     * @return số click thực sự được ghi mới (không tính event trùng hoặc có short code không còn tồn tại)
     */
    int ingest(List<ClickEvent> events);
}
