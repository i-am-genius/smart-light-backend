package com.genius.smartlight.service.ai.impl;

import com.genius.smartlight.service.ai.MainColorResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class MainColorServiceImplTest {

    private final MainColorServiceImpl service = new MainColorServiceImpl();

    @ParameterizedTest(name = "pink rgb({0},{1},{2}) stays neutral-slightly-warm")
    @CsvSource({
            "232, 190, 194",
            "255, 182, 193",
            "255, 192, 203",
            "255, 105, 180",
            "255, 20, 147"
    })
    void recommendsNeutralSlightlyWarmTemperatureForPink(int r, int g, int b)
            throws IOException {
        MainColorResult result = extractSolidColor(r, g, b);

        assertThat(result.getRecommendedTemp()).isBetween(4200, 4600);
    }

    @ParameterizedTest(name = "warm rgb({0},{1},{2}) is warmer than pink")
    @CsvSource({
            "255, 0, 0",
            "255, 165, 0"
    })
    void keepsRedAndOrangeWarmWithoutUsingExtremeMinimum(int r, int g, int b)
            throws IOException {
        MainColorResult result = extractSolidColor(r, g, b);

        assertThat(result.getRecommendedTemp()).isBetween(3500, 3900);
    }

    @Test
    void recommendsCoolTemperatureForBlue() throws IOException {
        MainColorResult blue = extractSolidColor(0, 0, 255);
        MainColorResult pink = extractSolidColor(255, 192, 203);

        assertThat(blue.getRecommendedTemp()).isBetween(5200, 5500);
        assertThat(blue.getRecommendedTemp()).isGreaterThan(pink.getRecommendedTemp());
    }

    @Test
    void returnsNeutralBaselineForGray() throws IOException {
        MainColorResult result = extractSolidColor(128, 128, 128);

        assertThat(result.getRecommendedTemp()).isEqualTo(4200);
    }

    @ParameterizedTest(name = "representative rgb({0},{1},{2}) remains in safe range")
    @CsvSource({
            "255, 0, 0",
            "255, 165, 0",
            "255, 255, 0",
            "0, 128, 0",
            "0, 255, 255",
            "0, 0, 255",
            "128, 0, 128",
            "128, 128, 128"
    })
    void keepsEveryRepresentativeColorInRecommendationRange(int r, int g, int b)
            throws IOException {
        MainColorResult result = extractSolidColor(r, g, b);

        assertThat(result.getRecommendedTemp()).isBetween(3500, 5500);
    }

    @Test
    void preservesInvalidImageFallback() {
        MainColorResult result = service.extract(
                new ByteArrayInputStream(new byte[]{0x01, 0x02, 0x03})
        );

        assertThat(result.getMainColorRgb()).isEqualTo("128,128,128");
        assertThat(result.getRecommendedBrightness()).isEqualTo(60);
        assertThat(result.getRecommendedTemp()).isEqualTo(4500);
    }

    private MainColorResult extractSolidColor(int r, int g, int b) throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(r, g, b));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", output)).isTrue();
        return service.extract(new ByteArrayInputStream(output.toByteArray()));
    }
}
