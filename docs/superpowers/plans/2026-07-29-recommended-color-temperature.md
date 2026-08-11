# Recommended Color Temperature Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the over-amplified warm/cool score with a continuous CIELAB hue-anchor model so pink garments stay near neutral white while saturated garment colors retain a useful 3500–5500K recommendation range.

**Architecture:** Keep the public `MainColorService` contract and image-processing pipeline unchanged. `MainColorServiceImpl` continues converting the dominant RGB color to CIELAB, then computes color temperature by circular interpolation between fixed hue anchors and blends that value toward a 4200K neutral baseline with a smooth chroma factor.

**Tech Stack:** Java 17, Spring Boot 4.0.5, JUnit 5, AssertJ, Maven Wrapper

## Global Constraints

- Common pink colors must produce 4200–4600K.
- The main-color algorithm must clamp recommendations to 3500–5500K.
- Low-chroma colors with `C* <= 8` must return the 4200K neutral baseline.
- Full hue influence applies at `C* >= 50`, with smoothstep interpolation between `C* = 8` and `C* = 50`.
- Do not change REST interfaces, WebSocket fields, device protocol, `/ws/device`, device report endpoints, or ESP firmware.
- Preserve the existing fabric-specific adjustment in `GarmentRecognitionProcessor`.
- Preserve the existing invalid-image default result: RGB `128,128,128`, brightness `60`, temperature `4500`.
- Use the existing sRGB-to-CIELAB conversion in `MainColorServiceImpl`; do not add dependencies.

---

## File Structure

- Create `src/test/java/com/genius/smartlight/service/ai/impl/MainColorServiceImplTest.java`
  - Exercises the public `extract(InputStream)` path with generated solid-color PNGs.
  - Locks down pink, warm-color, blue, neutral, bounds, and invalid-image behavior.
- Modify `src/main/java/com/genius/smartlight/service/ai/impl/MainColorServiceImpl.java`
  - Owns hue anchors, chroma blending, circular interpolation, and final clamping.
  - Removes the obsolete warm/cool-center score and `angularDistance` helper.

### Task 1: Replace the color-temperature recommendation model

**Files:**
- Create: `src/test/java/com/genius/smartlight/service/ai/impl/MainColorServiceImplTest.java`
- Modify: `src/main/java/com/genius/smartlight/service/ai/impl/MainColorServiceImpl.java:20-25`
- Modify: `src/main/java/com/genius/smartlight/service/ai/impl/MainColorServiceImpl.java:213-272`

**Interfaces:**
- Consumes: `MainColorResult MainColorServiceImpl.extract(InputStream inputStream)`
- Produces: unchanged `MainColorResult` values through the existing public service contract
- Internal helpers: `double interpolateHueTemp(double hue)` and `double calcChromaFactor(double chroma)`

- [ ] **Step 1: Create the failing public-path regression tests**

Create `src/test/java/com/genius/smartlight/service/ai/impl/MainColorServiceImplTest.java` with:

```java
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
```

- [ ] **Step 2: Run the target test and verify RED**

Run:

```powershell
.\mvnw.cmd -Dtest=MainColorServiceImplTest test
```

Expected: `FAILURE`. The pink test must report current values around 2700–2800K outside `4200..4600`; the red/orange test may also report the current 2700K clamp outside `3500..3900`. The failure must come from recommendation assertions, not compilation or image generation.

- [ ] **Step 3: Add explicit algorithm constants**

In `MainColorServiceImpl`, immediately after `MERGE_DISTANCE`, add:

```java
    private static final int BASE_RECOMMENDED_TEMP = 4200;
    private static final int MIN_RECOMMENDED_TEMP = 3500;
    private static final int MAX_RECOMMENDED_TEMP = 5500;
    private static final double NEUTRAL_CHROMA = 8.0;
    private static final double FULL_CHROMA = 50.0;

    private static final double[] HUE_ANCHORS = {
            0.0, 20.0, 40.0, 75.0, 105.0, 140.0, 200.0, 306.0, 330.0, 360.0
    };
    private static final int[] HUE_TEMP_ANCHORS = {
            4450, 4450, 3600, 3500, 4000, 4400, 5000, 5500, 4900, 4450
    };
```

These arrays must remain the same length and sorted by ascending hue. The equal first and last temperature values close the circular interpolation boundary.

- [ ] **Step 4: Replace the over-amplified temperature calculation**

Replace the current `calcRecommendedTemp` method and remove `angularDistance`. Use:

```java
    /**
     * 根据主色的 CIELAB 色相和色度计算推荐色温。
     * 低色度颜色回归中性基准，高色度颜色平滑靠近对应色相锚点。
     */
    private int calcRecommendedTemp(int r, int g, int b) {
        double[] lab = rgbToLab(r, g, b);
        double a = lab[1];
        double bLab = lab[2];
        double chroma = Math.sqrt(a * a + bLab * bLab);

        double hue = Math.toDegrees(Math.atan2(bLab, a));
        if (hue < 0) {
            hue += 360;
        }

        double hueTemp = interpolateHueTemp(hue);
        double chromaFactor = calcChromaFactor(chroma);
        int temp = (int) Math.round(
                BASE_RECOMMENDED_TEMP
                        + chromaFactor * (hueTemp - BASE_RECOMMENDED_TEMP)
        );

        return clamp(temp, MIN_RECOMMENDED_TEMP, MAX_RECOMMENDED_TEMP);
    }

    private double interpolateHueTemp(double hue) {
        for (int i = 1; i < HUE_ANCHORS.length; i++) {
            if (hue <= HUE_ANCHORS[i]) {
                double startHue = HUE_ANCHORS[i - 1];
                double endHue = HUE_ANCHORS[i];
                double ratio = (hue - startHue) / (endHue - startHue);
                return HUE_TEMP_ANCHORS[i - 1]
                        + ratio * (HUE_TEMP_ANCHORS[i] - HUE_TEMP_ANCHORS[i - 1]);
            }
        }
        return HUE_TEMP_ANCHORS[0];
    }

    private double calcChromaFactor(double chroma) {
        if (chroma <= NEUTRAL_CHROMA) {
            return 0;
        }
        if (chroma >= FULL_CHROMA) {
            return 1;
        }

        double x = (chroma - NEUTRAL_CHROMA) / (FULL_CHROMA - NEUTRAL_CHROMA);
        return x * x * (3 - 2 * x);
    }
```

- [ ] **Step 5: Run the target test and verify GREEN**

Run:

```powershell
.\mvnw.cmd -Dtest=MainColorServiceImplTest test
```

Expected: `BUILD SUCCESS`, with all `MainColorServiceImplTest` cases passing and no warnings caused by the new code.

- [ ] **Step 6: Run related garment-processing regression tests**

Run:

```powershell
.\mvnw.cmd -Dtest=GarmentRecognitionProcessorTest test
```

Expected: `BUILD SUCCESS`. Existing cotton, polyester, wool, cashmere, aggregation, and clamp expectations remain unchanged.

- [ ] **Step 7: Run the complete test suite**

Run:

```powershell
.\mvnw.cmd test
```

Expected: `BUILD SUCCESS` with zero failed tests and zero test errors.

- [ ] **Step 8: Compile using the repository-required command**

Run:

```powershell
.\mvnw.cmd compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 9: Review the focused diff**

Run:

```powershell
git diff --check -- src/main/java/com/genius/smartlight/service/ai/impl/MainColorServiceImpl.java src/test/java/com/genius/smartlight/service/ai/impl/MainColorServiceImplTest.java
git diff -- src/main/java/com/genius/smartlight/service/ai/impl/MainColorServiceImpl.java src/test/java/com/genius/smartlight/service/ai/impl/MainColorServiceImplTest.java
```

Expected: `git diff --check` prints nothing. The focused diff contains only the new test class and replacement of the recommendation formula; it does not change dominant-color extraction, brightness, service interfaces, protocol fields, or fabric adjustment.

- [ ] **Step 10: Commit the verified implementation**

Run:

```powershell
git add -- src/main/java/com/genius/smartlight/service/ai/impl/MainColorServiceImpl.java src/test/java/com/genius/smartlight/service/ai/impl/MainColorServiceImplTest.java
git commit -m "fix(ai): improve recommended color temperature"
```

Expected: one commit containing exactly the production and test files listed above.
