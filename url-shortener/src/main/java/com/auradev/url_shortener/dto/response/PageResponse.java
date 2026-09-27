package com.auradev.url_shortener.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Wrapper response cho danh sách có phân trang.
 * Chuẩn hoá cấu trúc trả về khi dùng Spring Data {@link org.springframework.data.domain.Page}.
 *
 * @param <T> Kiểu dữ liệu của từng phần tử trong danh sách
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    /** Danh sách phần tử của trang hiện tại */
    private List<T> content;

    /** Số trang hiện tại (0-indexed) */
    private int page;

    /** Số phần tử trên một trang */
    private int size;

    /** Tổng số phần tử */
    private long totalElements;

    /** Tổng số trang */
    private int totalPages;

    /** Có trang tiếp theo không */
    private boolean hasNext;

    /** Có trang trước không */
    private boolean hasPrevious;

    /** Có phải trang đầu tiên không */
    private boolean first;

    /** Có phải trang cuối cùng không */
    private boolean last;

    /**
     * Tạo {@link PageResponse} từ {@link org.springframework.data.domain.Page} của Spring Data.
     */
    public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> page) {
        return PageResponse.<T>builder()
                .content(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .first(page.isFirst())
                .last(page.isLast())
                .build();
    }
}
