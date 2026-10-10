package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.config.ShortenerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Sinh short code từ một số tuần tự (lấy từ {@code short_code_seq}).
 *
 * <p>Cách hoạt động:
 * <ol>
 *   <li>Số tuần tự {@code n} (0 .. 62^7 - 1) được hoán vị bằng mạng Feistel có khoá (HMAC-SHA256)
 *       nên các số liên tiếp cho ra mã trông ngẫu nhiên, không đoán/đếm được.</li>
 *   <li>Miền 62^7 không phải luỹ thừa của 2 nên dùng <i>cycle walking</i>: hoán vị trên miền 2^42
 *       và lặp lại cho đến khi kết quả rơi vào [0, 62^7).</li>
 *   <li>Kết quả được mã hoá Base62, đệm '0' ở đầu cho đủ {@value #CODE_LENGTH} ký tự.</li>
 * </ol>
 *
 * <p>Vì là <b>song ánh</b> (mỗi {@code n} cho đúng một mã và ngược lại) nên hai số khác nhau
 * không bao giờ cho cùng một mã — không cần kiểm tra trùng khi sinh. Mạng Feistel ở đây phục vụ
 * che dấu thứ tự, không phải mã hoá để giữ bí mật dữ liệu.
 */
@Component
public class ShortCodeGenerator {

    public static final int CODE_LENGTH = 7;

    /** Số tuần tự tối đa + 1: 62^7 */
    public static final long DOMAIN_SIZE = 3_521_614_606_208L;

    private static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 2^42 > 62^7, chia làm hai nửa 21 bit */
    private static final int HALF_BITS = 21;
    private static final int HALF_MASK = (1 << HALF_BITS) - 1;
    private static final int ROUNDS = 6;

    private final SecretKeySpec key;

    @Autowired
    public ShortCodeGenerator(ShortenerProperties properties) {
        this(properties.getCodeSecret());
    }

    public ShortCodeGenerator(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "app.shortener.code-secret must be configured (env SHORT_CODE_SECRET)");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /**
     * @param sequence số tuần tự, trong khoảng [0, {@link #DOMAIN_SIZE})
     * @return mã gồm đúng {@value #CODE_LENGTH} ký tự Base62
     */
    public String generate(long sequence) {
        if (sequence < 0 || sequence >= DOMAIN_SIZE) {
            throw new IllegalArgumentException(
                    "Sequence out of range [0, " + DOMAIN_SIZE + "): " + sequence);
        }

        Mac mac = newMac();
        long value = sequence;
        do {
            value = permute(value, mac);
        } while (value >= DOMAIN_SIZE); // cycle walking

        return encode(value);
    }

    // ===========================
    //  PRIVATE HELPERS
    // ===========================

    /** Hoán vị trên miền [0, 2^42) bằng mạng Feistel. */
    private long permute(long x, Mac mac) {
        int left  = (int) ((x >>> HALF_BITS) & HALF_MASK);
        int right = (int) (x & HALF_MASK);

        for (int round = 0; round < ROUNDS; round++) {
            int next = left ^ roundFunction(right, round, mac);
            left  = right;
            right = next;
        }
        return ((long) left << HALF_BITS) | right;
    }

    private int roundFunction(int half, int round, Mac mac) {
        mac.update((byte) round);
        mac.update((byte) (half >>> 16));
        mac.update((byte) (half >>> 8));
        mac.update((byte) half);
        byte[] hash = mac.doFinal(); // doFinal reset Mac để dùng tiếp
        int value = ((hash[0] & 0xFF) << 16) | ((hash[1] & 0xFF) << 8) | (hash[2] & 0xFF);
        return value & HALF_MASK;
    }

    private String encode(long value) {
        char[] chars = new char[CODE_LENGTH];
        for (int i = CODE_LENGTH - 1; i >= 0; i--) {
            chars[i] = ALPHABET.charAt((int) (value % ALPHABET.length()));
            value /= ALPHABET.length();
        }
        return new String(chars);
    }

    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot initialise " + HMAC_ALGORITHM, e);
        }
    }
}
