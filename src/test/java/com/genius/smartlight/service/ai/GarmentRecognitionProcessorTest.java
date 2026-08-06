package com.genius.smartlight.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import com.genius.smartlight.vo.ai.GarmentPartRespVO;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GarmentRecognitionProcessorTest {

    private static final String SAMPLE_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M/wHwAF/gL+XwWvWQAAAABJRU5ErkJggg==";

    @Test
    void rejectsDuplicatePositions() {
        GarmentRecognitionProcessor processor = processorReturning("10,20,30", 60, 4500);
        FabricRecognizeRespVO result = result(
                "separates",
                garment("upper", "upper", 100, "cotton"),
                garment("upper", "upper", 80, "polyester")
        );

        assertInvalid(() -> processor.process(result));
    }

    @Test
    void enrichesEachPartAndWeightsLightingByMaskArea() {
        MainColorService colors = input -> new MainColorResult("20,40,60", 80, 5000);
        GarmentRecognitionProcessor processor = new GarmentRecognitionProcessor(colors);
        FabricRecognizeRespVO result = result(
                "separates",
                garment("upper", "upper", 100, "cotton"),
                garment("lower", "pants", 300, "polyester")
        );

        FabricRecognizeRespVO processed = processor.process(result);

        assertThat(processed).isSameAs(result);
        assertThat(result.getGarments()).allSatisfy(item -> {
            assertThat(item.getMainColorRgb()).isEqualTo("20,40,60");
            assertThat(item.getColorSamplePngBase64()).isNull();
        });
        assertThat(result.getRecommendedBrightness()).isEqualTo(78);
        assertThat(result.getRecommendedTemp()).isEqualTo(5138);
        assertThat(result.getLabel()).isEqualTo("polyester");
        assertThat(result.getConfidence()).isEqualTo(0.8);
        assertThat(result.getMainColorRgb()).isEqualTo("20,40,60");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validOutfits")
    void acceptsAllValidOutfitCombinations(String name, FabricRecognizeRespVO result) {
        GarmentRecognitionProcessor processor = processorReturning("10,20,30", 60, 4500);

        assertThatCode(() -> processor.process(result)).doesNotThrowAnyException();
    }

    private static Stream<Arguments> validOutfits() {
        return Stream.of(
                Arguments.of("upper_only", result(
                        "upper_only", garment("upper", "upper", 100, "cotton"))),
                Arguments.of("lower_only pants", result(
                        "lower_only", garment("lower", "pants", 100, "cotton"))),
                Arguments.of("lower_only skirt", result(
                        "lower_only", garment("lower", "skirt", 100, "cotton"))),
                Arguments.of("separates", result(
                        "separates",
                        garment("upper", "upper", 100, "cotton"),
                        garment("lower", "pants", 100, "cotton"))),
                Arguments.of("dress", result(
                        "dress", garment("fullBody", "dress", 100, "cotton")))
        );
    }

    @TestFactory
    Stream<DynamicTest> rejectsMissingOrInvalidTopLevelStructure() {
        List<NamedMutation> mutations = List.of(
                new NamedMutation("resultVersion null", value -> value.setResultVersion(null)),
                new NamedMutation("resultVersion unsupported", value -> value.setResultVersion(2)),
                new NamedMutation("clothDetected null", value -> value.setClothDetected(null)),
                new NamedMutation("segmentationFallback null", value -> value.setSegmentationFallback(null)),
                new NamedMutation("garments null", value -> value.setGarments(null)),
                new NamedMutation("garments empty", value -> value.setGarments(List.of())),
                new NamedMutation("outfitType null", value -> value.setOutfitType(null)),
                new NamedMutation("outfitType unknown", value -> value.setOutfitType("unknown"))
        );

        return mutations.stream().map(mutation -> DynamicTest.dynamicTest(mutation.name(), () -> {
            FabricRecognizeRespVO value = validUpperOnly();
            mutation.mutation().accept(value);
            assertInvalid(() -> processorReturning("1,2,3", 60, 4500).process(value));
        }));
    }

    @Test
    void rejectsNullResult() {
        assertInvalid(() -> processorReturning("1,2,3", 60, 4500).process(null));
    }

    @Test
    void rejectsMoreThanTwoGarments() {
        FabricRecognizeRespVO value = result(
                "separates",
                garment("upper", "upper", 10, "cotton"),
                garment("lower", "pants", 10, "cotton"),
                garment("fullBody", "dress", 10, "cotton")
        );

        assertInvalid(() -> processorReturning("1,2,3", 60, 4500).process(value));
    }

    @TestFactory
    Stream<DynamicTest> rejectsInvalidPartFields() {
        List<NamedPartMutation> mutations = List.of(
                new NamedPartMutation("part null", (value, part) ->
                        value.setGarments(Collections.singletonList(null))),
                new NamedPartMutation("position null", (value, part) -> part.setPosition(null)),
                new NamedPartMutation("position unknown", (value, part) -> part.setPosition("middle")),
                new NamedPartMutation("category null", (value, part) -> part.setCategory(null)),
                new NamedPartMutation("category unknown", (value, part) -> part.setCategory("coat")),
                new NamedPartMutation("maskArea null", (value, part) -> part.setMaskArea(null)),
                new NamedPartMutation("maskArea zero", (value, part) -> part.setMaskArea(0)),
                new NamedPartMutation("x null", (value, part) -> part.setX(null)),
                new NamedPartMutation("x negative", (value, part) -> part.setX(-1)),
                new NamedPartMutation("y null", (value, part) -> part.setY(null)),
                new NamedPartMutation("y negative", (value, part) -> part.setY(-1)),
                new NamedPartMutation("w null", (value, part) -> part.setW(null)),
                new NamedPartMutation("w zero", (value, part) -> part.setW(0)),
                new NamedPartMutation("h null", (value, part) -> part.setH(null)),
                new NamedPartMutation("h zero", (value, part) -> part.setH(0)),
                new NamedPartMutation("fabric null", (value, part) -> part.setFabric(null)),
                new NamedPartMutation("fabric blank", (value, part) -> part.setFabric("  ")),
                new NamedPartMutation("fabric confidence null", (value, part) -> part.setFabricConfidence(null)),
                new NamedPartMutation("fabric confidence below zero", (value, part) -> part.setFabricConfidence(-0.01)),
                new NamedPartMutation("fabric confidence above one", (value, part) -> part.setFabricConfidence(1.01)),
                new NamedPartMutation("category confidence null", (value, part) -> part.setCategoryConfidence(null)),
                new NamedPartMutation("category confidence below zero", (value, part) -> part.setCategoryConfidence(-0.01)),
                new NamedPartMutation("category confidence above one", (value, part) -> part.setCategoryConfidence(1.01))
        );

        return mutations.stream().map(mutation -> DynamicTest.dynamicTest(mutation.name(), () -> {
            FabricRecognizeRespVO value = validUpperOnly();
            GarmentPartRespVO part = value.getGarments().get(0);
            mutation.mutation().accept(value, part);
            assertInvalid(() -> processorReturning("1,2,3", 60, 4500).process(value));
        }));
    }

    @Test
    void acceptsCoordinateAreaAndConfidenceBoundaries() {
        GarmentPartRespVO upper = garment("upper", "upper", 1, "silk");
        upper.setX(0);
        upper.setY(0);
        upper.setW(1);
        upper.setH(1);
        upper.setCategoryConfidence(0.0);
        upper.setFabricConfidence(1.0);
        GarmentPartRespVO lower = garment("lower", "skirt", 1, "silk");
        lower.setCategoryConfidence(1.0);
        lower.setFabricConfidence(0.0);

        assertThatCode(() -> processorReturning("1,2,3", 60, 4500).process(
                result("separates", upper, lower)
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsNaNFabricConfidence() {
        FabricRecognizeRespVO value = validUpperOnly();
        value.getGarments().get(0).setFabricConfidence(Double.NaN);

        assertInvalid(() -> processorReturning("1,2,3", 60, 4500).process(value));
    }

    @Test
    void rejectsNaNCategoryConfidence() {
        FabricRecognizeRespVO value = validUpperOnly();
        value.getGarments().get(0).setCategoryConfidence(Double.NaN);

        assertInvalid(() -> processorReturning("1,2,3", 60, 4500).process(value));
    }

    @ParameterizedTest
    @MethodSource("infiniteConfidences")
    void rejectsInfiniteFabricConfidence(double confidence) {
        FabricRecognizeRespVO value = validUpperOnly();
        value.getGarments().get(0).setFabricConfidence(confidence);

        assertInvalid(() -> processorReturning("1,2,3", 60, 4500).process(value));
    }

    @ParameterizedTest
    @MethodSource("infiniteConfidences")
    void rejectsInfiniteCategoryConfidence(double confidence) {
        FabricRecognizeRespVO value = validUpperOnly();
        value.getGarments().get(0).setCategoryConfidence(confidence);

        assertInvalid(() -> processorReturning("1,2,3", 60, 4500).process(value));
    }

    private static Stream<Double> infiniteConfidences() {
        return Stream.of(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    @Test
    void acceptsBoundingBoxEndpointAtIntegerMaximum() {
        GarmentPartRespVO upper = garment("upper", "upper", 1, "silk");
        upper.setX(Integer.MAX_VALUE - 1);
        upper.setY(Integer.MAX_VALUE - 1);
        upper.setW(1);
        upper.setH(1);

        assertThatCode(() -> processorReturning("1,2,3", 60, 4500).process(
                result("upper_only", upper)
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsBoundingBoxEndpointOverflow() {
        GarmentPartRespVO upper = garment("upper", "upper", 1, "silk");
        upper.setX(Integer.MAX_VALUE);
        upper.setW(1);

        assertInvalid(() -> processorReturning("1,2,3", 60, 4500).process(
                result("upper_only", upper)
        ));
    }

    @Test
    void rejectsVerticalBoundingBoxEndpointOverflow() {
        GarmentPartRespVO upper = garment("upper", "upper", 1, "silk");
        upper.setY(Integer.MAX_VALUE);
        upper.setH(1);

        assertInvalid(() -> processorReturning("1,2,3", 60, 4500).process(
                result("upper_only", upper)
        ));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidOutfitCombinations")
    void rejectsInconsistentOutfitCombinations(String name, FabricRecognizeRespVO value) {
        assertInvalid(() -> processorReturning("1,2,3", 60, 4500).process(value));
    }

    private static Stream<Arguments> invalidOutfitCombinations() {
        return Stream.of(
                Arguments.of("upper_only requires upper/upper",
                        result("upper_only", garment("lower", "pants", 10, "cotton"))),
                Arguments.of("lower_only requires lower position",
                        result("lower_only", garment("upper", "upper", 10, "cotton"))),
                Arguments.of("lower_only requires pants or skirt",
                        result("lower_only", garment("lower", "upper", 10, "cotton"))),
                Arguments.of("separates requires upper and lower positions",
                        result(
                                "separates",
                                garment("upper", "upper", 10, "cotton"),
                                garment("fullBody", "dress", 10, "cotton"))),
                Arguments.of("separates upper category must match",
                        result(
                                "separates",
                                garment("upper", "pants", 10, "cotton"),
                                garment("lower", "skirt", 10, "cotton"))),
                Arguments.of("separates lower category must match",
                        result(
                                "separates",
                                garment("upper", "upper", 10, "cotton"),
                                garment("lower", "dress", 10, "cotton"))),
                Arguments.of("dress requires fullBody/dress",
                        result("dress", garment("upper", "upper", 10, "cotton")))
        );
    }

    @Test
    void acceptsFallbackUpperWithNullCategoryConfidence() {
        FabricRecognizeRespVO value = validUpperOnly();
        value.setSegmentationFallback(true);
        value.setClothDetected(false);
        value.getGarments().get(0).setCategoryConfidence(null);

        assertThatCode(() -> processorReturning("1,2,3", 60, 4500).process(value))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "clothDetected={0}, segmentationFallback={1}")
    @CsvSource({
            "true, false",
            "false, true"
    })
    void acceptsComplementaryDetectionFlags(
            boolean clothDetected,
            boolean segmentationFallback) {
        FabricRecognizeRespVO value = validUpperOnly();
        value.setClothDetected(clothDetected);
        value.setSegmentationFallback(segmentationFallback);
        value.getGarments().get(0).setCategoryConfidence(
                segmentationFallback ? null : 0.9
        );

        assertThatCode(() -> processorReturning("1,2,3", 60, 4500).process(value))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "clothDetected={0}, segmentationFallback={1}")
    @CsvSource({
            "false, false",
            "true, true"
    })
    void rejectsNonComplementaryDetectionFlags(
            boolean clothDetected,
            boolean segmentationFallback) {
        FabricRecognizeRespVO value = validUpperOnly();
        value.setClothDetected(clothDetected);
        value.setSegmentationFallback(segmentationFallback);
        value.getGarments().get(0).setCategoryConfidence(
                segmentationFallback ? null : 0.9
        );

        assertInvalid(() -> processorReturning("1,2,3", 60, 4500).process(value));
    }

    @Test
    void rejectsFallbackUpperWithNonNullCategoryConfidence() {
        FabricRecognizeRespVO value = validUpperOnly();
        value.setSegmentationFallback(true);
        value.setClothDetected(false);
        value.getGarments().get(0).setCategoryConfidence(0.5);

        assertInvalid(() -> processorReturning("1,2,3", 60, 4500).process(value));
    }

    @TestFactory
    Stream<DynamicTest> rejectsInconsistentFallbackStructure() {
        List<NamedMutation> mutations = List.of(
                new NamedMutation("fallback must report no cloth", value -> {
                    value.setSegmentationFallback(true);
                    value.setClothDetected(true);
                }),
                new NamedMutation("fallback must be upper_only", value -> {
                    value.setSegmentationFallback(true);
                    value.setClothDetected(false);
                    value.setOutfitType("lower_only");
                    GarmentPartRespVO lower = garment("lower", "pants", 10, "cotton");
                    value.setGarments(List.of(lower));
                }),
                new NamedMutation("fallback non-null category confidence stays bounded", value -> {
                    value.setSegmentationFallback(true);
                    value.setClothDetected(false);
                    value.getGarments().get(0).setCategoryConfidence(1.01);
                })
        );

        return mutations.stream().map(mutation -> DynamicTest.dynamicTest(mutation.name(), () -> {
            FabricRecognizeRespVO value = validUpperOnly();
            mutation.mutation().accept(value);
            assertInvalid(() -> processorReturning("1,2,3", 60, 4500).process(value));
        }));
    }

    @Test
    void colorSampleCanBeDeserializedButIsNeverSerialized() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        GarmentPartRespVO part = mapper.readValue(
                """
                        {"position":"upper","colorSamplePngBase64":"secret-base64"}
                        """,
                GarmentPartRespVO.class
        );

        assertThat(part.getColorSamplePngBase64()).isEqualTo("secret-base64");
        assertThat(mapper.writeValueAsString(part)).doesNotContain("colorSamplePngBase64", "secret-base64");
    }

    @Test
    void invalidBase64FallsBackToGrayWithoutAffectingOtherPart() {
        MainColorService colors = input -> new MainColorResult("20,40,60", 80, 5000);
        GarmentRecognitionProcessor processor = new GarmentRecognitionProcessor(colors);
        GarmentPartRespVO upper = garment("upper", "upper", 100, "silk");
        GarmentPartRespVO lower = garment("lower", "pants", 100, "silk");
        lower.setColorSamplePngBase64("not-valid-base64!");
        FabricRecognizeRespVO value = result("separates", upper, lower);

        processor.process(value);

        assertThat(upper.getMainColorRgb()).isEqualTo("20,40,60");
        assertThat(lower.getMainColorRgb()).isEqualTo("128,128,128");
        assertThat(value.getRecommendedBrightness()).isEqualTo(70);
        assertThat(value.getRecommendedTemp()).isEqualTo(4750);
        assertThat(value.getGarments()).allSatisfy(part ->
                assertThat(part.getColorSamplePngBase64()).isNull());
    }

    @Test
    void invalidCottonSampleKeepsExactDefaultWhileNormalPolyesterIsAdjusted() {
        MainColorService colors = input -> new MainColorResult("20,40,60", 80, 5000);
        GarmentPartRespVO upper = garment("upper", "upper", 100, "cotton");
        upper.setColorSamplePngBase64("not-valid-base64!");
        GarmentPartRespVO lower = garment("lower", "pants", 100, "polyester");
        FabricRecognizeRespVO value = result("separates", upper, lower);

        new GarmentRecognitionProcessor(colors).process(value);

        assertThat(upper.getMainColorRgb()).isEqualTo("128,128,128");
        assertThat(lower.getMainColorRgb()).isEqualTo("20,40,60");
        assertThat(value.getRecommendedBrightness()).isEqualTo(68);
        assertThat(value.getRecommendedTemp()).isEqualTo(4825);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void extractionFailureLogDoesNotContainBase64(CapturedOutput output) {
        MainColorService colors = input -> {
            throw new IllegalStateException(SAMPLE_PNG_BASE64);
        };
        GarmentPartRespVO upper = garment("upper", "upper", 100, "silk");

        new GarmentRecognitionProcessor(colors).process(result("upper_only", upper));

        assertThat(output).contains("position=upper", "exceptionType=IllegalStateException");
        assertThat(output).doesNotContain(SAMPLE_PNG_BASE64);
    }

    @Test
    void serviceFailureFallsBackForOnlyTheFailingPart() {
        AtomicInteger calls = new AtomicInteger();
        MainColorService colors = input -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("sensitive input must not be logged");
            }
            return new MainColorResult("7,8,9", 70, 4000);
        };
        GarmentRecognitionProcessor processor = new GarmentRecognitionProcessor(colors);
        GarmentPartRespVO upper = garment("upper", "upper", 100, "silk");
        GarmentPartRespVO lower = garment("lower", "pants", 100, "silk");

        processor.process(result("separates", upper, lower));

        assertThat(upper.getMainColorRgb()).isEqualTo("128,128,128");
        assertThat(lower.getMainColorRgb()).isEqualTo("7,8,9");
        assertThat(upper.getColorSamplePngBase64()).isNull();
        assertThat(lower.getColorSamplePngBase64()).isNull();
    }

    @Test
    void polyesterServiceFailureKeepsExactDefaultWhileNormalCottonIsAdjusted() {
        AtomicInteger calls = new AtomicInteger();
        MainColorService colors = input -> {
            if (calls.getAndIncrement() == 0) {
                return new MainColorResult("7,8,9", 80, 5000);
            }
            throw new IllegalStateException("failed");
        };
        GarmentPartRespVO upper = garment("upper", "upper", 100, "cotton");
        GarmentPartRespVO lower = garment("lower", "pants", 100, "polyester");
        FabricRecognizeRespVO value = result("separates", upper, lower);

        new GarmentRecognitionProcessor(colors).process(value);

        assertThat(upper.getMainColorRgb()).isEqualTo("7,8,9");
        assertThat(lower.getMainColorRgb()).isEqualTo("128,128,128");
        assertThat(value.getRecommendedBrightness()).isEqualTo(73);
        assertThat(value.getRecommendedTemp()).isEqualTo(4800);
    }

    @Test
    void missingColorSampleFallsBackToGray() {
        GarmentPartRespVO upper = garment("upper", "upper", 100, "cotton");
        upper.setColorSamplePngBase64(" ");
        FabricRecognizeRespVO value = result("upper_only", upper);

        processorReturning("1,2,3", 80, 5000).process(value);

        assertThat(upper.getMainColorRgb()).isEqualTo("128,128,128");
        assertThat(value.getRecommendedBrightness()).isEqualTo(60);
        assertThat(value.getRecommendedTemp()).isEqualTo(4500);
    }

    @Test
    void nullColorResultKeepsExactDefaultWithoutPolyesterAdjustment() {
        GarmentPartRespVO upper = garment("upper", "upper", 100, "polyester");
        FabricRecognizeRespVO value = result("upper_only", upper);

        new GarmentRecognitionProcessor(input -> null).process(value);

        assertThat(upper.getMainColorRgb()).isEqualTo("128,128,128");
        assertThat(value.getRecommendedBrightness()).isEqualTo(60);
        assertThat(value.getRecommendedTemp()).isEqualTo(4500);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fabricsThatWouldAdjustDefault")
    void defaultTripletReturnedByServiceSkipsFabricAdjustment(String fabric) {
        GarmentPartRespVO upper = garment("upper", "upper", 100, fabric);
        FabricRecognizeRespVO value = result("upper_only", upper);
        MainColorService colors =
                input -> new MainColorResult("128,128,128", 60, 4500);

        new GarmentRecognitionProcessor(colors).process(value);

        assertThat(upper.getMainColorRgb()).isEqualTo("128,128,128");
        assertThat(value.getRecommendedBrightness()).isEqualTo(60);
        assertThat(value.getRecommendedTemp()).isEqualTo(4500);
    }

    private static Stream<String> fabricsThatWouldAdjustDefault() {
        return Stream.of("cotton", "polyester");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonDefaultColorResults")
    void anyNonDefaultColorFieldStillReceivesFabricAdjustment(
            String name,
            MainColorResult color,
            String expectedRgb,
            int expectedBrightness,
            int expectedTemp
    ) {
        GarmentPartRespVO upper = garment("upper", "upper", 100, "cotton");
        FabricRecognizeRespVO value = result("upper_only", upper);

        new GarmentRecognitionProcessor(input -> color).process(value);

        assertThat(upper.getMainColorRgb()).isEqualTo(expectedRgb);
        assertThat(value.getRecommendedBrightness()).isEqualTo(expectedBrightness);
        assertThat(value.getRecommendedTemp()).isEqualTo(expectedTemp);
    }

    private static Stream<Arguments> nonDefaultColorResults() {
        return Stream.of(
                Arguments.of(
                        "RGB differs",
                        new MainColorResult("127,128,128", 60, 4500),
                        "127,128,128",
                        65,
                        4600
                ),
                Arguments.of(
                        "brightness differs",
                        new MainColorResult("128,128,128", 61, 4500),
                        "128,128,128",
                        66,
                        4600
                ),
                Arguments.of(
                        "temperature differs",
                        new MainColorResult("128,128,128", 60, 4501),
                        "128,128,128",
                        65,
                        4601
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fabricAdjustments")
    void preservesFabricLightingAdjustments(
            String fabric,
            int expectedBrightness,
            int expectedTemp
    ) {
        FabricRecognizeRespVO value = result(
                "upper_only", garment("upper", "upper", 100, fabric));

        processorReturning("1,2,3", 80, 5000).process(value);

        assertThat(value.getRecommendedBrightness()).isEqualTo(expectedBrightness);
        assertThat(value.getRecommendedTemp()).isEqualTo(expectedTemp);
    }

    private static Stream<Arguments> fabricAdjustments() {
        return Stream.of(
                Arguments.of("cotton", 85, 5100),
                Arguments.of("polyester", 75, 5150),
                Arguments.of("wool", 77, 4750),
                Arguments.of("cashmere", 77, 4750)
        );
    }

    @Test
    void clampsAdjustedAndAggregatedLighting() {
        FabricRecognizeRespVO high = result(
                "upper_only", garment("upper", "upper", 100, "cotton"));
        FabricRecognizeRespVO low = result(
                "upper_only", garment("upper", "upper", 100, "wool"));

        processorReturning("1,2,3", 200, 7000).process(high);
        processorReturning("1,2,3", -100, 100).process(low);

        assertThat(high.getRecommendedBrightness()).isEqualTo(95);
        assertThat(high.getRecommendedTemp()).isEqualTo(6500);
        assertThat(low.getRecommendedBrightness()).isEqualTo(30);
        assertThat(low.getRecommendedTemp()).isEqualTo(2700);
    }

    @Test
    void usesLargestGarmentForLegacyFieldsAndUnionsBoundingBoxes() {
        AtomicInteger calls = new AtomicInteger();
        MainColorService colors = input -> calls.getAndIncrement() == 0
                ? new MainColorResult("1,2,3", 60, 4500)
                : new MainColorResult("4,5,6", 60, 4500);
        GarmentPartRespVO upper = garment("upper", "upper", 100, "cotton");
        upper.setFabricConfidence(0.7);
        upper.setX(10);
        upper.setY(20);
        upper.setW(30);
        upper.setH(40);
        GarmentPartRespVO lower = garment("lower", "pants", 300, "polyester");
        lower.setFabricConfidence(0.95);
        lower.setX(5);
        lower.setY(50);
        lower.setW(100);
        lower.setH(20);
        FabricRecognizeRespVO value = result("separates", upper, lower);

        new GarmentRecognitionProcessor(colors).process(value);

        assertThat(value.getLabel()).isEqualTo("polyester");
        assertThat(value.getConfidence()).isEqualTo(0.95);
        assertThat(value.getMainColorRgb()).isEqualTo("4,5,6");
        assertThat(value.getClothX()).isEqualTo(5);
        assertThat(value.getClothY()).isEqualTo(20);
        assertThat(value.getClothW()).isEqualTo(100);
        assertThat(value.getClothH()).isEqualTo(50);
    }

    private static GarmentRecognitionProcessor processorReturning(
            String rgb,
            int brightness,
            int temp
    ) {
        return new GarmentRecognitionProcessor(
                input -> new MainColorResult(rgb, brightness, temp)
        );
    }

    private static FabricRecognizeRespVO validUpperOnly() {
        return result("upper_only", garment("upper", "upper", 100, "cotton"));
    }

    private static FabricRecognizeRespVO result(
            String outfitType,
            GarmentPartRespVO... garments
    ) {
        FabricRecognizeRespVO result = new FabricRecognizeRespVO();
        result.setResultVersion(1);
        result.setClothDetected(true);
        result.setSegmentationFallback(false);
        result.setOutfitType(outfitType);
        result.setGarments(List.of(garments));
        return result;
    }

    private static GarmentPartRespVO garment(
            String position,
            String category,
            int area,
            String fabric
    ) {
        GarmentPartRespVO item = new GarmentPartRespVO();
        item.setPosition(position);
        item.setCategory(category);
        item.setCategoryConfidence(0.9);
        item.setFabric(fabric);
        item.setFabricConfidence(0.8);
        item.setMaskArea(area);
        item.setX(1);
        item.setY(2);
        item.setW(10);
        item.setH(20);
        item.setColorSamplePngBase64(SAMPLE_PNG_BASE64);
        return item;
    }

    private static void assertInvalid(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("服装识别结果结构无效");
    }

    private record NamedMutation(
            String name,
            Consumer<FabricRecognizeRespVO> mutation
    ) {
    }

    private record NamedPartMutation(
            String name,
            PartMutation mutation
    ) {
    }

    @FunctionalInterface
    private interface PartMutation {
        void accept(FabricRecognizeRespVO value, GarmentPartRespVO part);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
