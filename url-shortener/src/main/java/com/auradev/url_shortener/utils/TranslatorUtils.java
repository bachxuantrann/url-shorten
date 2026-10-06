package com.auradev.url_shortener.utils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

/**
 * Utility bean để resolve i18n message theo locale của request hiện tại.
 */
@Component
@RequiredArgsConstructor
public class TranslatorUtils {

    private final MessageSource messageSource;
    private final LocaleResolver localeResolver;

    /**
     * Dịch message key thành chuỗi theo locale của request hiện tại.
     *
     * @param msgCode message key trong file i18n (vd: "api.error.not_found")
     * @param args    tham số bổ sung cho message (optional)
     */
    public String toLocale(String msgCode, Object... args) {
        Locale locale;
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            // Đọc trực tiếp từ request để đảm bảo Filter (như Security) cũng lấy đúng cấu hình LocaleResolver
            HttpServletRequest request = attributes.getRequest();
            locale = localeResolver.resolveLocale(request);
        } else {
            // Fallback nếu không có request (ví dụ chạy background task)
            locale = LocaleContextHolder.getLocale();
        }
        return messageSource.getMessage(msgCode, args, locale);
    }
}