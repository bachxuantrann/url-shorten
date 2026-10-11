package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.exception.AppException;
import com.auradev.url_shortener.exception.ErrorCode;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Phân tích host của URL đích để chặn địa chỉ nội bộ/loopback và so khớp blocklist.
 *
 * <p><b>Không bao giờ phân giải DNS</b>: mọi kiểm tra là thuần cú pháp, nên nhanh, không bị
 * treo bởi DNS và không rò rỉ thông tin. Hệ quả: một tên miền công khai được cấu hình DNS trỏ về
 * IP nội bộ (ví dụ {@code localtest.me}) không bị phát hiện. Điều này chấp nhận được vì server
 * chỉ <i>redirect trình duyệt của người truy cập</i>, không tự gọi URL đích; nếu sau này server
 * tự tải URL (xem trước link, quét malware) thì phải kiểm tra lại IP sau khi phân giải.
 */
public final class HostUtils {

    /** Dạng số thập phân/hex/bát phân kiểu {@code inet_aton}: 2130706433, 0x7f.1, 0177.0.0.1, 127.1 */
    private static final Pattern NUMERIC_HOST =
            Pattern.compile("^(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]+|[0-9]+)){0,3}$");

    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-f:.]+$");

    private static final Pattern DOMAIN =
            Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");

    /** Hậu tố tên miền chỉ có nghĩa trong mạng nội bộ. */
    private static final List<String> INTERNAL_SUFFIXES =
            List.of(".localhost", ".local", ".internal", ".localdomain", ".home.arpa", ".lan");

    private HostUtils() {
    }

    // ===========================
    //  NORMALISATION
    // ===========================

    /**
     * Chữ thường, bỏ dấu chấm cuối ({@code example.com.}) và ngoặc vuông của IPv6 ({@code [::1]}).
     *
     * @return {@code null} nếu {@code host} là null
     */
    public static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h;
    }

    /**
     * Chuẩn hoá domain do admin nhập: bỏ khoảng trắng, {@code *.}/{@code .} ở đầu, dấu chấm cuối,
     * chữ thường; phải có ít nhất hai nhãn và đúng cú pháp tên miền.
     *
     * @throws AppException {@link ErrorCode#INVALID_DOMAIN}
     */
    public static String normalizeDomain(String raw) {
        String d = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (d.startsWith("*.")) {
            d = d.substring(2);
        }
        while (d.startsWith(".")) {
            d = d.substring(1);
        }
        while (d.endsWith(".")) {
            d = d.substring(0, d.length() - 1);
        }
        if (d.isEmpty() || d.length() > 253 || !DOMAIN.matcher(d).matches()) {
            throw new AppException(ErrorCode.INVALID_DOMAIN, raw);
        }
        return d;
    }

    // ===========================
    //  PRIVATE / INTERNAL HOSTS
    // ===========================

    /** Host (đã {@link #normalizeHost chuẩn hoá}) là địa chỉ literal (IPv4 mọi dạng, hoặc IPv6). */
    public static boolean isIpLiteral(String host) {
        return host.contains(":") || NUMERIC_HOST.matcher(host).matches();
    }

    /**
     * Host (đã {@link #normalizeHost chuẩn hoá}) trỏ vào máy cục bộ hoặc mạng nội bộ: loopback,
     * dải riêng, link-local (cloud metadata 169.254.169.254), multicast/reserved, tên miền nội bộ
     * và host chỉ có một nhãn ({@code intranet}, {@code router}...).
     */
    public static boolean isPrivateOrInternalHost(String host) {
        if (host.contains(":")) {
            return isPrivateIpv6(host);
        }
        if (NUMERIC_HOST.matcher(host).matches()) {
            Long address = parseIpv4(host);
            // Dạng số không hợp lệ/mơ hồ (ví dụ 0999.1): chặn cho chắc
            return address == null || isPrivateIpv4(address);
        }
        if (host.equals("localhost") || INTERNAL_SUFFIXES.stream().anyMatch(host::endsWith)) {
            return true;
        }
        return !host.contains(".");
    }

    /**
     * Parse theo quy tắc {@code inet_aton}: 1-4 phần; phần có tiền tố {@code 0x} là hex, bắt đầu
     * bằng {@code 0} là bát phân; phần cuối chiếm toàn bộ các byte còn lại.
     *
     * @return địa chỉ 32 bit, hoặc {@code null} nếu không hợp lệ
     */
    static Long parseIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length < 1 || parts.length > 4) {
            return null;
        }
        long address = 0;
        for (int i = 0; i < parts.length; i++) {
            Long value = parseIpv4Part(parts[i]);
            if (value == null) {
                return null;
            }
            boolean last = i == parts.length - 1;
            if (last) {
                long limit = 1L << (8 * (5 - parts.length));
                if (value >= limit) {
                    return null;
                }
                address += value;
            } else {
                if (value > 255) {
                    return null;
                }
                address += value << (8 * (3 - i));
            }
        }
        return address;
    }

    private static Long parseIpv4Part(String part) {
        try {
            if (part.startsWith("0x")) {
                return Long.parseLong(part.substring(2), 16);
            }
            if (part.length() > 1 && part.startsWith("0")) {
                return Long.parseLong(part, 8);
            }
            return Long.parseLong(part);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean isPrivateIpv4(long address) {
        int o1 = (int) ((address >> 24) & 255);
        int o2 = (int) ((address >> 16) & 255);
        return o1 == 0                                  // 0.0.0.0/8 ("this network")
                || o1 == 10                             // 10.0.0.0/8
                || o1 == 127                            // loopback
                || (o1 == 169 && o2 == 254)             // link-local, cloud metadata
                || (o1 == 172 && o2 >= 16 && o2 <= 31)  // 172.16.0.0/12
                || (o1 == 192 && o2 == 168)             // 192.168.0.0/16
                || (o1 == 100 && o2 >= 64 && o2 <= 127) // carrier-grade NAT 100.64.0.0/10
                || (o1 == 198 && (o2 == 18 || o2 == 19)) // benchmarking 198.18.0.0/15
                || o1 >= 224;                           // multicast + reserved + broadcast
    }

    private static boolean isPrivateIpv6(String host) {
        // Chỉ ký tự của địa chỉ IPv6 literal: nhờ vậy InetAddress.getByName không bao giờ gọi DNS
        if (!IPV6_CHARS.matcher(host).matches()) {
            return true;
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) { // IPv4-mapped (::ffff:a.b.c.d)
            return isPrivateIpv4(toLong(b, 0));
        }
        if (allZero(b, 0, 15) && (b[15] == 0 || b[15] == 1)) { // :: và ::1
            return true;
        }
        if (allZero(b, 0, 12)) {                                // IPv4-compatible ::a.b.c.d
            return isPrivateIpv4(toLong(b, 12));
        }
        if (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b && allZero(b, 4, 12)) {
            return isPrivateIpv4(toLong(b, 12));                // NAT64 64:ff9b::/96
        }
        if ((b[0] & 0xff) == 0x20 && b[1] == 0x02) {            // 6to4 2002::/16 mang IPv4 ở byte 2-5
            return isPrivateIpv4(toLong(b, 2));
        }
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        return (b0 == 0xfe && (b1 & 0xc0) == 0x80)  // link-local fe80::/10
                || (b0 == 0xfe && (b1 & 0xc0) == 0xc0) // site-local fec0::/10 (đã bỏ nhưng vẫn chặn)
                || (b0 & 0xfe) == 0xfc                // unique local fc00::/7
                || b0 == 0xff;                        // multicast
    }

    private static boolean allZero(byte[] bytes, int fromInclusive, int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static long toLong(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24)
                | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    // ===========================
    //  BLOCKLIST MATCHING
    // ===========================

    /**
     * Các domain cần tra trong blocklist cho một host: chính host và mọi domain cha có ít nhất
     * hai nhãn ({@code a.b.example.com} → {@code a.b.example.com, b.example.com, example.com}).
     * Địa chỉ IP literal chỉ so khớp chính xác.
     */
    public static List<String> blocklistCandidates(String host) {
        if (isIpLiteral(host)) {
            return List.of(host);
        }
        String[] labels = host.split("\\.");
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i <= labels.length - 2; i++) {
            candidates.add(String.join(".", java.util.Arrays.copyOfRange(labels, i, labels.length)));
        }
        return candidates;
    }

    /**
     * Regex POSIX (dùng với toán tử {@code ~*} của Postgres) khớp mọi URL có host là {@code domain}
     * hoặc subdomain của nó. {@code domain} phải đã qua {@link #normalizeDomain}.
     */
    public static String urlHostRegex(String domain) {
        return "^https?://([^/?#:@]+\\.)?" + domain.replace(".", "\\.") + "\\.?([:/?#]|$)";
    }
}
