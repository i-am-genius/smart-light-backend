package com.genius.smartlight.service.ai.impl;

import com.genius.smartlight.service.ai.MainColorResult;
import com.genius.smartlight.service.ai.MainColorService;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class MainColorServiceImpl implements MainColorService {

    /**
     * Mean Shift 参数
     */
    private static final int MAX_SAMPLES = 1400;
    private static final int MAX_ITERATIONS = 8;
    private static final double BANDWIDTH = 20.0;
    private static final double BANDWIDTH_SQUARE = BANDWIDTH * BANDWIDTH;
    private static final double MERGE_DISTANCE = 6.0;

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

    @Override
    public MainColorResult extract(InputStream inputStream) {
        try {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                return defaultResult();
            }

            List<ColorSample> samples = collectSamples(image);
            if (samples.isEmpty()) {
                return defaultResult();
            }

            List<ColorCluster> clusters = meanShiftCluster(samples);
            if (clusters.isEmpty()) {
                return defaultResult();
            }

            ColorCluster best = clusters.stream()
                    .max(Comparator.comparingDouble(ColorCluster::score))
                    .orElse(null);

            if (best == null || best.weight <= 0) {
                return defaultResult();
            }

            int r = clamp((int) Math.round(best.rSum / best.weight), 0, 255);
            int g = clamp((int) Math.round(best.gSum / best.weight), 0, 255);
            int b = clamp((int) Math.round(best.bSum / best.weight), 0, 255);

            String mainColorRgb = r + "," + g + "," + b;

            int recommendedBrightness = calcRecommendedBrightness(r, g, b);
            int recommendedTemp = calcRecommendedTemp(r, g, b);

            return new MainColorResult(mainColorRgb, recommendedBrightness, recommendedTemp);
        } catch (Exception e) {
            return defaultResult();
        }
    }

    /**
     * 采样并转换到 CIELAB 空间
     */
    private List<ColorSample> collectSamples(BufferedImage image) {
        List<ColorSample> samples = new ArrayList<>();

        int width = image.getWidth();
        int height = image.getHeight();

        int totalPixels = width * height;
        int step = Math.max(1, (int) Math.ceil(Math.sqrt(totalPixels * 1.0 / MAX_SAMPLES)));

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int argb = image.getRGB(x, y);

                int alpha = (argb >> 24) & 0xff;
                if (alpha < 150) {
                    continue;
                }

                int r = (argb >> 16) & 0xff;
                int g = (argb >> 8) & 0xff;
                int b = argb & 0xff;

                double[] lab = rgbToLab(r, g, b);

                /*
                 * 中心区域权重：
                 * 服装图片通常主体在中间，边缘更可能是背景。
                 * 这里不是直接裁掉边缘，而是降低边缘像素权重。
                 */
                double dx = (x - width / 2.0) / (width / 2.0);
                double dy = (y - height / 2.0) / (height / 2.0);
                double distanceFromCenter = dx * dx + dy * dy;
                double centerWeight = Math.exp(-2.6 * distanceFromCenter);

                double weight = 0.35 + 0.65 * centerWeight;

                samples.add(new ColorSample(
                        r, g, b,
                        lab[0], lab[1], lab[2],
                        weight
                ));
            }
        }

        return samples;
    }

    /**
     * Mean Shift 聚类
     */
    private List<ColorCluster> meanShiftCluster(List<ColorSample> samples) {
        List<ColorCluster> clusters = new ArrayList<>();

        for (ColorSample seed : samples) {
            double currentL = seed.l;
            double currentA = seed.a;
            double currentB = seed.bLab;

            for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
                double sumWeight = 0;
                double sumL = 0;
                double sumA = 0;
                double sumB = 0;

                for (ColorSample sample : samples) {
                    double distSquare = labDistanceSquare(
                            currentL, currentA, currentB,
                            sample.l, sample.a, sample.bLab
                    );

                    if (distSquare <= BANDWIDTH_SQUARE) {
                        double kernelWeight = 1.0 - distSquare / BANDWIDTH_SQUARE;
                        double w = sample.weight * kernelWeight;

                        sumWeight += w;
                        sumL += sample.l * w;
                        sumA += sample.a * w;
                        sumB += sample.bLab * w;
                    }
                }

                if (sumWeight <= 0) {
                    break;
                }

                double nextL = sumL / sumWeight;
                double nextA = sumA / sumWeight;
                double nextB = sumB / sumWeight;

                double shift = Math.sqrt(labDistanceSquare(
                        currentL, currentA, currentB,
                        nextL, nextA, nextB
                ));

                currentL = nextL;
                currentA = nextA;
                currentB = nextB;

                if (shift < 0.3) {
                    break;
                }
            }

            ColorCluster matched = null;

            for (ColorCluster cluster : clusters) {
                double dist = Math.sqrt(labDistanceSquare(
                        currentL, currentA, currentB,
                        cluster.modeL, cluster.modeA, cluster.modeB
                ));

                if (dist < MERGE_DISTANCE) {
                    matched = cluster;
                    break;
                }
            }

            if (matched == null) {
                matched = new ColorCluster(currentL, currentA, currentB);
                clusters.add(matched);
            }

            matched.add(seed, currentL, currentA, currentB);
        }

        return clusters;
    }

    /**
     * 根据主色计算推荐亮度
     */
    private int calcRecommendedBrightness(int r, int g, int b) {
        double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;

        int minBrightness = 45;
        int maxBrightness = 100;

        int brightness = (int) Math.round(
                minBrightness + (1 - luminance / 255.0) * (maxBrightness - minBrightness)
        );

        return clamp(brightness, minBrightness, maxBrightness);
    }

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

    private MainColorResult defaultResult() {
        return new MainColorResult("128,128,128", 60, 4500);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * RGB 转 CIELAB
     */
    private double[] rgbToLab(int r, int g, int b) {
        double[] xyz = rgbToXyz(r, g, b);

        double x = xyz[0] / 0.95047;
        double y = xyz[1] / 1.00000;
        double z = xyz[2] / 1.08883;

        double fx = labF(x);
        double fy = labF(y);
        double fz = labF(z);

        double l = 116 * fy - 16;
        double a = 500 * (fx - fy);
        double bb = 200 * (fy - fz);

        return new double[]{l, a, bb};
    }

    /**
     * RGB 转 XYZ，D65 标准光源
     */
    private double[] rgbToXyz(int r, int g, int b) {
        double rf = srgbToLinear(r / 255.0);
        double gf = srgbToLinear(g / 255.0);
        double bf = srgbToLinear(b / 255.0);

        double x = 0.4124564 * rf + 0.3575761 * gf + 0.1804375 * bf;
        double y = 0.2126729 * rf + 0.7151522 * gf + 0.0721750 * bf;
        double z = 0.0193339 * rf + 0.1191920 * gf + 0.9503041 * bf;

        return new double[]{x, y, z};
    }

    private double srgbToLinear(double c) {
        if (c <= 0.04045) {
            return c / 12.92;
        }
        return Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private double labF(double t) {
        if (t > 0.008856) {
            return Math.cbrt(t);
        }
        return 7.787 * t + 16.0 / 116.0;
    }

    private double labDistanceSquare(
            double l1, double a1, double b1,
            double l2, double a2, double b2
    ) {
        double dl = l1 - l2;
        double da = a1 - a2;
        double db = b1 - b2;
        return dl * dl + da * da + db * db;
    }

    private static class ColorSample {
        int r;
        int g;
        int b;

        double l;
        double a;
        double bLab;

        double weight;

        ColorSample(int r, int g, int b, double l, double a, double bLab, double weight) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.l = l;
            this.a = a;
            this.bLab = bLab;
            this.weight = weight;
        }
    }

    private static class ColorCluster {
        double modeL;
        double modeA;
        double modeB;

        double weight;

        double rSum;
        double gSum;
        double bSum;

        double lSum;
        double aSum;
        double bLabSum;

        ColorCluster(double modeL, double modeA, double modeB) {
            this.modeL = modeL;
            this.modeA = modeA;
            this.modeB = modeB;
        }

        double modeWeight;

        void add(ColorSample sample, double finalL, double finalA, double finalB) {
            double w = sample.weight;

            weight += w;

            rSum += sample.r * w;
            gSum += sample.g * w;
            bSum += sample.b * w;

            lSum += sample.l * w;
            aSum += sample.a * w;
            bLabSum += sample.bLab * w;

            double newModeWeight = modeWeight + w;

            modeL = (modeL * modeWeight + finalL * w) / newModeWeight;
            modeA = (modeA * modeWeight + finalA * w) / newModeWeight;
            modeB = (modeB * modeWeight + finalB * w) / newModeWeight;

            modeWeight = newModeWeight;
        }

        double score() {
            if (weight <= 0) {
                return 0;
            }

            double avgA = aSum / weight;
            double avgB = bLabSum / weight;

            /*
             * CIELAB 中 chroma 表示颜色鲜明程度。
             * 这里给高色度颜色一点轻微加权，但不过度排斥黑白灰。
             */
            double chroma = Math.sqrt(avgA * avgA + avgB * avgB);
            double chromaBonus = 1.0 + 0.12 * Math.min(chroma / 80.0, 1.0);

            return weight * chromaBonus;
        }
    }
}
