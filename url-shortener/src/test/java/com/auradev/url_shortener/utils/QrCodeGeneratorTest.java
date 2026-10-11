package com.auradev.url_shortener.utils;

import com.auradev.url_shortener.support.QrDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QrCodeGeneratorTest {

    private final QrCodeGenerator generator = new QrCodeGenerator();

    @Test
    void producesAPngThatDecodesBackToTheContent() throws Exception {
        byte[] png = generator.png("https://sho.rt/aB3xK9z", 320);

        // 8 byte đầu của mọi file PNG
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(decode(png)).isEqualTo("https://sho.rt/aB3xK9z");
    }

    @Test
    void theImageIsTheRequestedSquareSize() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(generator.png("https://sho.rt/abc", 256)));

        assertThat(image.getWidth()).isEqualTo(256);
        assertThat(image.getHeight()).isEqualTo(256);
    }

    @ParameterizedTest
    @ValueSource(ints = {QrCodeGenerator.MIN_SIZE, QrCodeGenerator.DEFAULT_SIZE, QrCodeGenerator.MAX_SIZE})
    void everySupportedSizeStaysScannable(int size) throws Exception {
        assertThat(decode(generator.png("https://sho.rt/my-github_01", size))).isEqualTo("https://sho.rt/my-github_01");
    }

    @Test
    void handlesLongUrlsAndNonAsciiContent() throws Exception {
        String url = "https://sho.rt/" + "a".repeat(200) + "?q=xin-chào";

        assertThat(decode(generator.png(url, 512))).isEqualTo(url);
    }

    @Test
    void isDeterministicForTheSameInput() {
        assertThat(generator.png("https://sho.rt/aB3xK9z", 300)).isEqualTo(generator.png("https://sho.rt/aB3xK9z", 300));
    }

    @Test
    void rejectsSizesOutsideTheSupportedRange() {
        assertThatThrownBy(() -> generator.png("x", QrCodeGenerator.MIN_SIZE - 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.png("x", QrCodeGenerator.MAX_SIZE + 1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static String decode(byte[] png) throws Exception {
        return QrDecoder.decode(png);
    }
}
