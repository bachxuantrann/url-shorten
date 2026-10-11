package com.auradev.url_shortener.utils;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Sinh mã QR dạng PNG cho một chuỗi (ở đây là short URL). Sinh tại chỗ mỗi lần gọi và <b>không lưu</b>:
 * mã QR chỉ phụ thuộc vào nội dung nên không có gì cần lưu, và không có trạng thái nào để lệch với link.
 *
 * <p>Dùng mức sửa lỗi M (khôi phục được ~15% bị che) và vùng trống (quiet zone) 4 module theo
 * khuyến nghị của chuẩn để máy quét đọc ổn định. Chỉ phụ thuộc {@code zxing-core}, tự vẽ ảnh bằng
 * {@link BufferedImage} nên không kéo thêm thư viện.
 */
@Component
public class QrCodeGenerator {

    public static final int MIN_SIZE     = 128;
    public static final int DEFAULT_SIZE = 320;
    public static final int MAX_SIZE     = 1024;

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;

    /**
     * @param size cạnh của ảnh vuông, tính bằng pixel, trong khoảng [{@value #MIN_SIZE}, {@value #MAX_SIZE}]
     * @return nội dung file PNG
     */
    public byte[] png(String content, int size) {
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException("QR size must be between " + MIN_SIZE + " and " + MAX_SIZE + ": " + size);
        }

        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, Map.of(
                    EncodeHintType.CHARACTER_SET, "UTF-8",
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 4));
        } catch (WriterException e) {
            throw new IllegalStateException("Cannot encode QR code", e);
        }

        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        for (int x = 0; x < matrix.getWidth(); x++) {
            for (int y = 0; y < matrix.getHeight(); y++) {
                image.setRGB(x, y, matrix.get(x, y) ? BLACK : WHITE);
            }
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write QR code PNG", e);
        }
    }
}
