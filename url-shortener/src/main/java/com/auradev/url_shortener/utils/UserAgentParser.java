package com.auradev.url_shortener.utils;

import ua_parser.Client;
import ua_parser.Parser;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phân loại User-Agent thành thiết bị / trình duyệt / hệ điều hành.
 *
 * <p>Trình duyệt và hệ điều hành do thư viện uap-java (bộ regex của ua-parser) nhận diện, rồi được chuẩn
 * hoá về một tên duy nhất ("Chrome Mobile" và "Chrome" đều thành "Chrome") để thống kê không bị tách
 * nhỏ. Loại thiết bị ({@code DESKTOP/MOBILE/TABLET/BOT}) uap-java không cung cấp nên được suy ra từ
 * family của thiết bị, hệ điều hành và các dấu hiệu trong chuỗi UA. Giá trị trả về luôn nằm trong giới
 * hạn độ dài cột của {@code url_clicks}.
 */
public final class UserAgentParser {

    public static final String UNKNOWN = "Unknown";

    public static final String DEVICE_DESKTOP = "DESKTOP";
    public static final String DEVICE_MOBILE  = "MOBILE";
    public static final String DEVICE_TABLET  = "TABLET";
    public static final String DEVICE_BOT     = "BOT";
    public static final String DEVICE_UNKNOWN = "UNKNOWN";

    public record Parsed(String deviceType, String browser, String os) {
    }

    private static final Parsed UNKNOWN_UA = new Parsed(DEVICE_UNKNOWN, UNKNOWN, UNKNOWN);

    /** Parser không có trạng thái thay đổi sau khi tạo nên dùng chung giữa các thread; nạp regex một lần. */
    private static final Parser PARSER = new Parser();

    /** Crawler, trình xem trước link và client HTTP script: không phải người dùng thật. */
    private static final List<String> BOT_MARKERS = List.of(
            "bot", "spider", "crawl", "slurp", "facebookexternalhit", "whatsapp", "preview",
            "headlesschrome", "lighthouse", "curl/", "wget/", "python-requests", "python-urllib",
            "go-http-client", "okhttp", "libwww", "httpclient", "uptime");

    private static final Map<String, String> BROWSER_NAMES = Map.ofEntries(
            Map.entry("Chrome Mobile", "Chrome"),
            Map.entry("Chrome Mobile iOS", "Chrome"),
            Map.entry("Chrome Mobile WebView", "Chrome"),
            Map.entry("Mobile Safari", "Safari"),
            Map.entry("Mobile Safari UI/WKWebView", "Safari"),
            Map.entry("Firefox Mobile", "Firefox"),
            Map.entry("Firefox iOS", "Firefox"),
            Map.entry("Edge Mobile", "Edge"),
            Map.entry("Opera Mobile", "Opera"),
            Map.entry("Opera Mini", "Opera"),
            Map.entry("Samsung Internet", "Samsung Internet"),
            Map.entry("IE", "Internet Explorer"),
            Map.entry("IE Mobile", "Internet Explorer"));

    private static final Map<String, String> OS_NAMES = Map.of(
            "Mac OS X", "macOS",
            "Chrome OS", "ChromeOS");

    private static final int MAX_NAME_LENGTH = 32;

    private UserAgentParser() {
    }

    public static Parsed parse(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN_UA;
        }
        Client client = PARSER.parse(userAgent);
        String ua = userAgent.toLowerCase(Locale.ROOT);
        String os = osName(client);

        boolean bot = "Spider".equals(client.device.family) || BOT_MARKERS.stream().anyMatch(ua::contains);
        String device = bot ? DEVICE_BOT : device(ua, client, os);
        String browser = bot ? "Bot" : browserName(client);
        return new Parsed(device, browser, os);
    }

    private static String device(String ua, Client client, String os) {
        String deviceFamily = client.device.family == null ? "" : client.device.family.toLowerCase(Locale.ROOT);
        if (ua.contains("ipad") || ua.contains("tablet") || deviceFamily.contains("ipad") || deviceFamily.contains("tablet")
                || ("Android".equals(os) && !ua.contains("mobile"))) {
            return DEVICE_TABLET;
        }
        if (ua.contains("mobi") || ua.contains("iphone") || ua.contains("ipod") || "Android".equals(os) || "iOS".equals(os)) {
            return DEVICE_MOBILE;
        }
        return DEVICE_DESKTOP;
    }

    private static String browserName(Client client) {
        String family = client.userAgent.family;
        if (family == null || family.isBlank() || "Other".equals(family)) {
            return "Other";
        }
        return limit(BROWSER_NAMES.getOrDefault(family, family));
    }

    private static String osName(Client client) {
        String family = client.os.family;
        if (family == null || family.isBlank() || "Other".equals(family)) {
            return "Other";
        }
        return limit(OS_NAMES.getOrDefault(family, family));
    }

    private static String limit(String value) {
        return value.length() <= MAX_NAME_LENGTH ? value : value.substring(0, MAX_NAME_LENGTH);
    }
}
