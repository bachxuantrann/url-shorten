package com.auradev.url_shortener.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Cấu hình i18n và các bean chung của ứng dụng.
 */
@Configuration
public class AppConfig {

    /**
     * Đọc ngôn ngữ từ header {@code Accept-Language}.
     * Mặc định: English nếu không có header hoặc ngôn ngữ không được hỗ trợ.
     * Hỗ trợ: {@code en}, {@code vi}.
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(Locale.ENGLISH, Locale.forLanguageTag("vi")));
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }

    /**
     * Cấu hình {@link MessageSource} tường minh để đảm bảo encoding UTF-8
     * và fallback về message mặc định khi không tìm thấy key.
     */
    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource messageSource =
                new ReloadableResourceBundleMessageSource();
        messageSource.setBasename("classpath:i18n/message");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setUseCodeAsDefaultMessage(false);
        messageSource.setCacheSeconds(3600); // Cache 1 giờ ở production
        return messageSource;
    }
}
