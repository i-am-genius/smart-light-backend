package com.genius.smartlight.service.ai;

import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import com.genius.smartlight.vo.ai.GarmentPartRespVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class GarmentRecognitionProcessor {

    private static final Set<String> POSITIONS = Set.of("upper", "lower", "fullBody");
    private static final Set<String> CATEGORIES = Set.of("upper", "pants", "skirt", "dress");
    private static final Set<String> OUTFIT_TYPES =
            Set.of("upper_only", "lower_only", "separates", "dress");
    private static final MainColorResult DEFAULT_COLOR =
            new MainColorResult("128,128,128", 60, 4500);

    private final MainColorService mainColorService;

    public FabricRecognizeRespVO process(FabricRecognizeRespVO result) {
        validate(result);
        List<PartLighting> lighting = new ArrayList<>();
        for (GarmentPartRespVO part : result.getGarments()) {
            String colorSample = part.getColorSamplePngBase64();
            part.setColorSamplePngBase64(null);
            ColorExtraction extraction = extractColor(colorSample, part.getPosition());
            MainColorResult adjusted = extraction.fallback()
                    ? extraction.color()
                    : applyFabricAdjustment(extraction.color(), part.getFabric());
            part.setMainColorRgb(adjusted.getMainColorRgb());
            lighting.add(new PartLighting(part.getMaskArea(), adjusted));
        }
        applyAggregate(result, lighting);
        applyLegacyFields(result);
        return result;
    }

    private void validate(FabricRecognizeRespVO result) {
        if (result == null
                || !Integer.valueOf(1).equals(result.getResultVersion())
                || result.getClothDetected() == null
                || result.getSegmentationFallback() == null
                || result.getGarments() == null
                || result.getGarments().isEmpty()
                || result.getGarments().size() > 2
                || result.getOutfitType() == null
                || !OUTFIT_TYPES.contains(result.getOutfitType())) {
            throw invalid();
        }

        boolean segmentationFallback = result.getSegmentationFallback();
        boolean clothDetected = result.getClothDetected();
        if (segmentationFallback == clothDetected) {
            throw invalid();
        }

        Set<String> positions = new HashSet<>();
        for (GarmentPartRespVO part : result.getGarments()) {
            if (part == null
                    || part.getPosition() == null
                    || !POSITIONS.contains(part.getPosition())
                    || part.getCategory() == null
                    || !CATEGORIES.contains(part.getCategory())
                    || !positions.add(part.getPosition())
                    || part.getMaskArea() == null
                    || part.getMaskArea() <= 0
                    || part.getX() == null
                    || part.getX() < 0
                    || part.getY() == null
                    || part.getY() < 0
                    || part.getW() == null
                    || part.getW() <= 0
                    || part.getH() == null
                    || part.getH() <= 0
                    || (long) part.getX() + part.getW() > Integer.MAX_VALUE
                    || (long) part.getY() + part.getH() > Integer.MAX_VALUE
                    || part.getFabric() == null
                    || part.getFabric().isBlank()
                    || !isValidConfidence(part.getFabricConfidence())) {
                throw invalid();
            }

            Double categoryConfidence = part.getCategoryConfidence();
            if (segmentationFallback
                    ? categoryConfidence != null
                    : !isValidConfidence(categoryConfidence)) {
                throw invalid();
            }
        }

        validateOutfitCombination(result);
        if (segmentationFallback && !"upper_only".equals(result.getOutfitType())) {
            throw invalid();
        }
    }

    private boolean isValidConfidence(Double confidence) {
        return confidence != null
                && Double.isFinite(confidence)
                && confidence >= 0
                && confidence <= 1;
    }

    private void validateOutfitCombination(FabricRecognizeRespVO result) {
        List<GarmentPartRespVO> garments = result.getGarments();
        boolean valid = switch (result.getOutfitType()) {
            case "upper_only" -> garments.size() == 1
                    && matches(garments.get(0), "upper", "upper");
            case "lower_only" -> garments.size() == 1
                    && "lower".equals(garments.get(0).getPosition())
                    && Set.of("pants", "skirt").contains(garments.get(0).getCategory());
            case "separates" -> garments.size() == 2 && hasValidSeparates(garments);
            case "dress" -> garments.size() == 1
                    && matches(garments.get(0), "fullBody", "dress");
            default -> false;
        };
        if (!valid) {
            throw invalid();
        }
    }

    private boolean hasValidSeparates(List<GarmentPartRespVO> garments) {
        GarmentPartRespVO upper = garments.stream()
                .filter(part -> "upper".equals(part.getPosition()))
                .findFirst()
                .orElse(null);
        GarmentPartRespVO lower = garments.stream()
                .filter(part -> "lower".equals(part.getPosition()))
                .findFirst()
                .orElse(null);
        return upper != null
                && lower != null
                && "upper".equals(upper.getCategory())
                && Set.of("pants", "skirt").contains(lower.getCategory());
    }

    private boolean matches(GarmentPartRespVO part, String position, String category) {
        return position.equals(part.getPosition()) && category.equals(part.getCategory());
    }

    private ColorExtraction extractColor(String colorSample, String position) {
        if (colorSample == null || colorSample.isBlank()) {
            return new ColorExtraction(DEFAULT_COLOR, true);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(colorSample);
            MainColorResult extracted = mainColorService.extract(new ByteArrayInputStream(decoded));
            return extracted == null || isDefaultColor(extracted)
                    ? new ColorExtraction(DEFAULT_COLOR, true)
                    : new ColorExtraction(extracted, false);
        } catch (Exception e) {
            log.warn("garment color extraction failed position={} exceptionType={}",
                    position, e.getClass().getSimpleName());
            return new ColorExtraction(DEFAULT_COLOR, true);
        }
    }

    private boolean isDefaultColor(MainColorResult color) {
        return Objects.equals(DEFAULT_COLOR.getMainColorRgb(), color.getMainColorRgb())
                && Objects.equals(
                        DEFAULT_COLOR.getRecommendedBrightness(),
                        color.getRecommendedBrightness()
                )
                && Objects.equals(DEFAULT_COLOR.getRecommendedTemp(), color.getRecommendedTemp());
    }

    private MainColorResult applyFabricAdjustment(MainColorResult colorResult, String fabric) {
        MainColorResult baseResult = colorResult == null ? DEFAULT_COLOR : colorResult;

        int brightness = baseResult.getRecommendedBrightness() == null
                ? 60
                : baseResult.getRecommendedBrightness();
        int temp = baseResult.getRecommendedTemp() == null
                ? 4500
                : baseResult.getRecommendedTemp();

        String normalizedFabric = normalizeFabric(fabric);
        if (normalizedFabric.contains("cotton")) {
            brightness += 5;
            temp += 100;
        } else if (normalizedFabric.contains("polyester")) {
            brightness -= 5;
            temp += 150;
        } else if (normalizedFabric.contains("wool")
                || normalizedFabric.contains("cashmere")) {
            brightness -= 3;
            temp -= 250;
        }

        return new MainColorResult(
                baseResult.getMainColorRgb(),
                clamp(brightness, 30, 95),
                clamp(temp, 2700, 6500)
        );
    }

    private String normalizeFabric(String fabric) {
        return fabric == null ? "" : fabric.trim().toLowerCase(Locale.ROOT);
    }

    private void applyAggregate(FabricRecognizeRespVO result, List<PartLighting> lighting) {
        long totalArea = lighting.stream().mapToLong(PartLighting::area).sum();
        int brightness = (int) Math.round(
                lighting.stream()
                        .mapToLong(item ->
                                (long) item.area() * item.color().getRecommendedBrightness())
                        .sum() / (double) totalArea
        );
        int temp = (int) Math.round(
                lighting.stream()
                        .mapToLong(item ->
                                (long) item.area() * item.color().getRecommendedTemp())
                        .sum() / (double) totalArea
        );
        result.setRecommendedBrightness(clamp(brightness, 30, 95));
        result.setRecommendedTemp(clamp(temp, 2700, 6500));
    }

    private void applyLegacyFields(FabricRecognizeRespVO result) {
        GarmentPartRespVO primary = result.getGarments().stream()
                .max(Comparator.comparingInt(GarmentPartRespVO::getMaskArea))
                .orElseThrow();
        result.setLabel(primary.getFabric());
        result.setConfidence(primary.getFabricConfidence());
        result.setMainColorRgb(primary.getMainColorRgb());

        int minX = result.getGarments().stream()
                .mapToInt(GarmentPartRespVO::getX)
                .min()
                .orElseThrow();
        int minY = result.getGarments().stream()
                .mapToInt(GarmentPartRespVO::getY)
                .min()
                .orElseThrow();
        int maxX = result.getGarments().stream()
                .mapToInt(part -> part.getX() + part.getW())
                .max()
                .orElseThrow();
        int maxY = result.getGarments().stream()
                .mapToInt(part -> part.getY() + part.getH())
                .max()
                .orElseThrow();
        result.setClothX(minX);
        result.setClothY(minY);
        result.setClothW(maxX - minX);
        result.setClothH(maxY - minY);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private ServiceException invalid() {
        return new ServiceException("服装识别结果结构无效");
    }

    private record PartLighting(int area, MainColorResult color) {
    }

    private record ColorExtraction(MainColorResult color, boolean fallback) {
    }
}
