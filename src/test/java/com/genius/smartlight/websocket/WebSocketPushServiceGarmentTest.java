package com.genius.smartlight.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.service.device.OtaProgressStore;
import com.genius.smartlight.service.ai.GarmentAimCalibrationFitter;
import com.genius.smartlight.service.device.GarmentAimCalibrationService;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import com.genius.smartlight.vo.ai.GarmentPartRespVO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketPushServiceGarmentTest {

    @Test
    void browserMessageIncludesGarmentsAndDeviceGetsOnlyCompactAimTarget() throws Exception {
        WebSocketSessionManager browser = mock(WebSocketSessionManager.class);
        DeviceSessionManager devices = mock(DeviceSessionManager.class);
        GarmentAimCalibrationService calibration = mock(GarmentAimCalibrationService.class);
        ObjectMapper mapper = new ObjectMapper();
        WebSocketPushService service = new WebSocketPushService(
                browser,
                mapper,
                devices,
                mock(OtaProgressStore.class),
                calibration
        );
        GarmentPartRespVO part = new GarmentPartRespVO();
        part.setPosition("lower");
        part.setCategory("skirt");
        part.setFabric("cotton");
        part.setMainColorRgb("10,20,30");
        part.setX(160);
        part.setY(120);
        part.setW(160);
        part.setH(240);
        part.setColorSamplePngBase64("secret-base64");
        FabricRecognizeRespVO result = new FabricRecognizeRespVO();
        result.setResultVersion(1);
        result.setClothDetected(true);
        result.setSegmentationFallback(false);
        result.setOutfitType("lower_only");
        result.setImageWidth(640);
        result.setImageHeight(480);
        result.setGarments(List.of(part));

        service.pushFabricRecognize("lamp-1", "x.jpg", result, 3L);

        ArgumentCaptor<String> browserJson = ArgumentCaptor.forClass(String.class);
        verify(browser).broadcastToStore(eq(3L), browserJson.capture());
        JsonNode browserPayload = mapper.readTree(browserJson.getValue()).path("data");
        assertThat(browserPayload.path("resultVersion").asInt()).isEqualTo(1);
        assertThat(browserPayload.path("segmentationFallback").asBoolean()).isFalse();
        assertThat(browserPayload.path("outfitType").asText()).isEqualTo("lower_only");
        assertThat(browserPayload.path("garments").size()).isEqualTo(1);
        assertThat(browserJson.getValue())
                .doesNotContain("colorSamplePngBase64", "secret-base64");

        DeviceRespVO state = new DeviceRespVO();
        state.setChipId("lamp-1");
        state.setGarmentAimEnabled(true);
        state.setGarmentDefaultPan(3D);
        state.setGarmentDefaultTilt(18D);
        state.setPersonDefaultPan(-4D);
        state.setPersonDefaultTilt(-28D);
        state.setClothDetected(true);
        state.setImageWidth(640);
        state.setImageHeight(480);
        state.setGarments(List.of(part));

        service.pushStateToDevice("lamp-1", state);

        ArgumentCaptor<String> deviceJson = ArgumentCaptor.forClass(String.class);
        verify(devices).sendToDevice(eq("lamp-1"), deviceJson.capture());
        JsonNode devicePayload = mapper.readTree(deviceJson.getValue()).path("data");
        assertThat(devicePayload.has("garments")).isFalse();
        assertThat(devicePayload.path("garmentAimEnabled").asBoolean()).isTrue();
        assertThat(devicePayload.path("garmentDefaultPan").asDouble()).isEqualTo(3D);
        assertThat(devicePayload.path("garmentDefaultTilt").asDouble()).isEqualTo(18D);
        assertThat(devicePayload.path("personDefaultPan").asDouble()).isEqualTo(-4D);
        assertThat(devicePayload.path("personDefaultTilt").asDouble()).isEqualTo(-28D);
        assertThat(devicePayload.path("garmentTargetValid").asBoolean()).isTrue();
        assertThat(devicePayload.path("garmentCenterX").asDouble()).isEqualTo(0.375D);
        assertThat(devicePayload.path("garmentCenterY").asDouble()).isEqualTo(0.5D);
        assertThat(devicePayload.path("garmentImageWidth").asInt()).isEqualTo(640);
        assertThat(devicePayload.path("garmentImageHeight").asInt()).isEqualTo(480);
        assertThat(devicePayload.path("garmentCalibrationValid").asBoolean()).isFalse();
        assertThat(devicePayload.has("garmentAimPan")).isFalse();
        assertThat(devicePayload.has("garmentAimTilt")).isFalse();
        assertThat(devicePayload.has("garmentAimSlider")).isFalse();
        verify(calibration, never()).predict(eq("lamp-1"), any());

        when(calibration.predict(eq("lamp-1"), eq("PHONE"), any()))
                .thenReturn(Optional.of(new GarmentAimCalibrationFitter.Pose(12D, -6D)));
        service.pushStateToDevice("lamp-1", state, "PHONE");

        ArgumentCaptor<String> sourceAwareDeviceJson = ArgumentCaptor.forClass(String.class);
        verify(devices, org.mockito.Mockito.times(2))
                .sendToDevice(eq("lamp-1"), sourceAwareDeviceJson.capture());
        JsonNode sourceAwarePayload = mapper.readTree(
                sourceAwareDeviceJson.getAllValues().get(1)
        ).path("data");
        assertThat(sourceAwarePayload.path("garmentCalibrationValid").asBoolean()).isTrue();
        assertThat(sourceAwarePayload.path("garmentAimPan").asDouble()).isEqualTo(12D);
        assertThat(sourceAwarePayload.path("garmentAimTilt").asDouble()).isEqualTo(-6D);
    }
}
