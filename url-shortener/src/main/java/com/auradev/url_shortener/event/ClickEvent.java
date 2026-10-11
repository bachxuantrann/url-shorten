package com.auradev.url_shortener.event;

import java.time.LocalDateTime;

/**
 * Một lượt click thành công (redirect 302 hoặc unlock đúng mật khẩu), gửi qua Kafka dạng JSON.
 *
 * <p>Kafka giao tối thiểu một lần nên {@code eventId} là khoá chống trùng của consumer, và
 * {@code clickedAt} được gán <b>lúc tạo event</b> (không phải lúc consumer xử lý) để một event giao
 * lại trúng đúng khoá {@code (event_id, clicked_at)}.
 *
 * <p>Không mang IP thô: {@code visitorHash} đã được băm có khoá ở phía producer.
 *
 * @param eventId     UUID duy nhất của event
 * @param shortCode   mã link (cũng là key Kafka, giữ thứ tự theo từng link)
 * @param clickedAt   thời điểm click
 * @param visitorHash định danh khách đã băm (trong ngày), null nếu không tính được
 * @param country     mã quốc gia ISO 2 ký tự do CDN cung cấp, null nếu không có
 * @param userAgent   User-Agent (đã cắt ngắn), consumer sẽ parse ra thiết bị/trình duyệt/OS
 */
public record ClickEvent(
        String eventId,
        String shortCode,
        LocalDateTime clickedAt,
        String visitorHash,
        String country,
        String userAgent
) {
}
