package com.genius.smartlight.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.service.device.OtaProgressStore;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import com.genius.smartlight.vo.ai.GarmentPartRespVO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebSocketPushServiceGarmentTest {

    @Test
    void browserFabricMessageIncludesGarmentsButDeviceStateDoesNot() throws Exception {
        WebSocketSessionManager browser = mock(WebSocketSessionManager.class);
        DeviceSessionManager devices = mock(DeviceSessionManager.class);
        ObjectMapper mapper = new ObjectMapper();
        WebSocketPushService service = new WebSocketPushService(
                browser,
                mapper,
                devices,
                mock(OtaProgressStore.class)
        );
        GarmentPartRespVO part = new GarmentPartRespVO();
        part.setPosition("lower");
        part.setCategory("skirt");
        part.setFabric("cotton");
        part.setMainColorRgb("10,20,30");
        part.setColorSamplePngBase64("secret-base64");
        FabricRecognizeRespVO result = new FabricRecognizeRespVO();
        result.setResultVersion(1);
        result.setSegmentationFallback(false);
        result.setOutfitType("lower_only");
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
        state.setGarments(List.of(part));
        service.pushStateToDevice("lamp-1", state);

        ArgumentCaptor<String> deviceJson = ArgumentCaptor.forClass(String.class);
        verify(devices).sendToDevice(eq("lamp-1"), deviceJson.capture());
        assertThat(mapper.readTree(deviceJson.getValue()).path("data").has("garments")).isFalse();
    }
}
