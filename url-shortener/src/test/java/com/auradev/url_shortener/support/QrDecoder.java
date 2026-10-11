package com.auradev.url_shortener.support;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * Giải mã mã QR từ PNG bằng ZXing để chứng minh ảnh thật sự quét được, không chỉ "trông giống QR".
 */
public final class QrDecoder {

    private QrDecoder() {
    }

    public static String decode(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        return new MultiFormatReader().decode(bitmap).getText();
    }

    public static BufferedImage image(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }
}
