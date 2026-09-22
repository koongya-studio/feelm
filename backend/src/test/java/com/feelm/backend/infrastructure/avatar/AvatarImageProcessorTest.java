package com.feelm.backend.infrastructure.avatar;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("프로필 사진 정규화")
class AvatarImageProcessorTest {

    private final AvatarImageProcessor processor = new AvatarImageProcessor();

    @Test
    @DisplayName("WEBP 입력을 512 정사각 JPEG로 변환한다")
    void normalizesWebp() throws Exception {
        byte[] webp =
                Base64.getDecoder()
                        .decode(
                                "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEALmk0mk0iIiIiIgBoSygABc6zbAAA");

        byte[] result = processor.normalize(webp);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));

        assertThat(result).startsWith((byte) 0xff, (byte) 0xd8);
        assertThat(decoded.getWidth()).isEqualTo(512);
        assertThat(decoded.getHeight()).isEqualTo(512);
    }

    @Test
    @DisplayName("투명 픽셀은 JPEG 변환 전에 흰색 배경과 합성한다")
    void compositesTransparencyOnWhite() throws Exception {
        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, new Color(0, 0, 0, 0).getRGB());
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        ImageIO.write(source, "png", png);

        BufferedImage result =
                ImageIO.read(new ByteArrayInputStream(processor.normalize(png.toByteArray())));
        Color pixel = new Color(result.getRGB(256, 256));

        assertThat(pixel.getRed()).isGreaterThan(245);
        assertThat(pixel.getGreen()).isGreaterThan(245);
        assertThat(pixel.getBlue()).isGreaterThan(245);
    }
}
