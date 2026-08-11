package com.genius.smartlight.service.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.convert.device.DeviceConvert;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import com.genius.smartlight.vo.ai.GarmentPartRespVO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GarmentResultCodecTest {

    private static final LocalDateTime RECOGNIZED_AT =
            LocalDateTime.of(2026, 7, 23, 12, 0);

    @Test
    void roundTripsAllPublicSnapshotFieldsWithoutInternalBase64() {
        FabricRecognizeRespVO result = recognitionResult(
                garment("upper", "shirt", 0.91, "cotton", 0.8,
                        "1,2,3", 120, 10, 20, 30, 40, "must-not-persist"));

        String json = GarmentResultCodec.encode(result, RECOGNIZED_AT);
        DeviceRespVO response = applyJson(7L, json);

        assertThat(json)
                .contains("\"recognizedAt\":\"2026-07-23T12:00:00\"")
                .doesNotContain("must-not-persist", "colorSamplePngBase64");
        assertThat(response.getResultVersion()).isEqualTo(1);
        assertThat(response.getClothDetected()).isFalse();
        assertThat(response.getSegmentationFallback()).isTrue();
        assertThat(response.getOutfitType()).isEqualTo("upper_only");
        assertThat(response.getImageWidth()).isEqualTo(640);
        assertThat(response.getImageHeight()).isEqualTo(480);
        assertThat(response.getGarments()).singleElement().satisfies(part -> {
            assertThat(part.getPosition()).isEqualTo("upper");
            assertThat(part.getCategory()).isEqualTo("shirt");
            assertThat(part.getCategoryConfidence()).isEqualTo(0.91);
            assertThat(part.getFabric()).isEqualTo("cotton");
            assertThat(part.getFabricConfidence()).isEqualTo(0.8);
            assertThat(part.getMainColorRgb()).isEqualTo("1,2,3");
            assertThat(part.getMaskArea()).isEqualTo(120);
            assertThat(part.getX()).isEqualTo(10);
            assertThat(part.getY()).isEqualTo(20);
            assertThat(part.getW()).isEqualTo(30);
            assertThat(part.getH()).isEqualTo(40);
            assertThat(part.getColorSamplePngBase64()).isNull();
        });
    }

    @Test
    void encodeDeepCopiesInsteadOfRelyingOnlyOnWriteOnlyAnnotation() {
        LeakyGarmentPartRespVO part = new LeakyGarmentPartRespVO();
        part.setPosition("upper");
        part.setCategory("shirt");
        part.setColorSamplePngBase64("leaky-base64");
        FabricRecognizeRespVO result = recognitionResult(part);

        String json = GarmentResultCodec.encode(result, RECOGNIZED_AT);

        assertThat(json).doesNotContain("leaky-base64", "colorSamplePngBase64");
    }

    @Test
    void nullJsonFallsBackToExactlyOneLegacyUpper() {
        DeviceDO device = legacyDevice(8L, "cotton", "1,2,3", null);
        DeviceRespVO response = new DeviceRespVO();

        GarmentResultCodec.applyToResponse(device, response);

        assertLegacyUpper(response, "cotton", "1,2,3");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void badJsonFallsBackWithoutLeakingJsonBody(CapturedOutput output) {
        String secretJsonBody = "{bad-secret-json-body";
        DeviceDO device = legacyDevice(9L, "polyester", "213,215,217", secretJsonBody);
        DeviceRespVO response = new DeviceRespVO();

        GarmentResultCodec.applyToResponse(device, response);

        assertLegacyUpper(response, "polyester", "213,215,217");
        assertThat(output).contains("deviceId=9", "exceptionType=");
        assertThat(output).doesNotContain(secretJsonBody, "bad-secret-json-body");
    }

    @Test
    void emptyLegacyScalarsDoNotInventGarments() {
        DeviceDO device = legacyDevice(10L, " ", "", null);
        DeviceRespVO response = new DeviceRespVO();

        GarmentResultCodec.applyToResponse(device, response);

        assertThat(response.getResultVersion()).isNull();
        assertThat(response.getClothDetected()).isNull();
        assertThat(response.getSegmentationFallback()).isNull();
        assertThat(response.getOutfitType()).isNull();
        assertThat(response.getGarments()).isNull();
    }

    @Test
    void encodeFailureIsReportedAsServiceException() {
        GarmentPartRespVO part = new GarmentPartRespVO() {
            @Override
            public String getPosition() {
                throw new IllegalStateException("copy failed");
            }
        };
        FabricRecognizeRespVO result = recognitionResult(part);

        assertThatThrownBy(() -> GarmentResultCodec.encode(result, RECOGNIZED_AT))
                .isInstanceOf(ServiceException.class)
                .hasMessage("服装识别结果序列化失败")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void deviceConvertAutomaticallyAppliesCodec() {
        FabricRecognizeRespVO result = recognitionResult(
                garment("lower", "pants", 0.88, "denim", 0.93,
                        "4,5,6", 200, 1, 2, 3, 4, null));
        result.setOutfitType("lower_only");
        DeviceDO device = legacyDevice(
                11L, "legacy-fabric", "99,99,99",
                GarmentResultCodec.encode(result, RECOGNIZED_AT));

        DeviceRespVO response = DeviceConvert.convert(device);

        assertThat(response.getOutfitType()).isEqualTo("lower_only");
        assertThat(response.getGarments()).singleElement().satisfies(part -> {
            assertThat(part.getPosition()).isEqualTo("lower");
            assertThat(part.getFabric()).isEqualTo("denim");
            assertThat(part.getMainColorRgb()).isEqualTo("4,5,6");
        });
    }

    @Test
    void validJsonIsNotOverwrittenByLegacyScalars() {
        FabricRecognizeRespVO result = recognitionResult(
                garment("upper", "shirt", 0.9, "silk", 0.95,
                        "7,8,9", 300, 5, 6, 7, 8, null));
        DeviceDO device = legacyDevice(
                12L, "legacy-cotton", "100,101,102",
                GarmentResultCodec.encode(result, RECOGNIZED_AT));

        DeviceRespVO response = new DeviceRespVO();
        GarmentResultCodec.applyToResponse(device, response);

        assertThat(response.getGarments()).singleElement().satisfies(part -> {
            assertThat(part.getFabric()).isEqualTo("silk");
            assertThat(part.getMainColorRgb()).isEqualTo("7,8,9");
        });
    }

    private static FabricRecognizeRespVO recognitionResult(GarmentPartRespVO part) {
        FabricRecognizeRespVO result = new FabricRecognizeRespVO();
        result.setResultVersion(1);
        result.setClothDetected(false);
        result.setSegmentationFallback(true);
        result.setOutfitType("upper_only");
        result.setImageWidth(640);
        result.setImageHeight(480);
        result.setGarments(List.of(part));
        return result;
    }

    private static GarmentPartRespVO garment(
            String position,
            String category,
            Double categoryConfidence,
            String fabric,
            Double fabricConfidence,
            String mainColorRgb,
            Integer maskArea,
            Integer x,
            Integer y,
            Integer w,
            Integer h,
            String colorSamplePngBase64) {
        GarmentPartRespVO part = new GarmentPartRespVO();
        part.setPosition(position);
        part.setCategory(category);
        part.setCategoryConfidence(categoryConfidence);
        part.setFabric(fabric);
        part.setFabricConfidence(fabricConfidence);
        part.setMainColorRgb(mainColorRgb);
        part.setMaskArea(maskArea);
        part.setX(x);
        part.setY(y);
        part.setW(w);
        part.setH(h);
        part.setColorSamplePngBase64(colorSamplePngBase64);
        return part;
    }

    private static DeviceRespVO applyJson(Long deviceId, String json) {
        DeviceDO device = new DeviceDO();
        device.setId(deviceId);
        device.setGarmentResultJson(json);
        DeviceRespVO response = new DeviceRespVO();
        GarmentResultCodec.applyToResponse(device, response);
        return response;
    }

    private static DeviceDO legacyDevice(
            Long id, String fabric, String mainColorRgb, String garmentResultJson) {
        DeviceDO device = new DeviceDO();
        device.setId(id);
        device.setFabric(fabric);
        device.setMainColorRgb(mainColorRgb);
        device.setGarmentResultJson(garmentResultJson);
        return device;
    }

    private static void assertLegacyUpper(
            DeviceRespVO response, String fabric, String mainColorRgb) {
        assertThat(response.getResultVersion()).isEqualTo(1);
        assertThat(response.getClothDetected()).isTrue();
        assertThat(response.getSegmentationFallback()).isFalse();
        assertThat(response.getOutfitType()).isEqualTo("upper_only");
        assertThat(response.getGarments()).singleElement().satisfies(part -> {
            assertThat(part.getPosition()).isEqualTo("upper");
            assertThat(part.getCategory()).isEqualTo("upper");
            assertThat(part.getFabric()).isEqualTo(fabric);
            assertThat(part.getMainColorRgb()).isEqualTo(mainColorRgb);
            assertThat(part.getMaskArea()).isEqualTo(1);
        });
    }

    private static final class LeakyGarmentPartRespVO extends GarmentPartRespVO {

        @Override
        @JsonProperty(access = JsonProperty.Access.READ_WRITE)
        public String getColorSamplePngBase64() {
            return super.getColorSamplePngBase64();
        }
    }
}
