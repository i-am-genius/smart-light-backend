package com.genius.smartlight.service.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.DurationRecordMapper;
import com.genius.smartlight.vo.device.DeviceCamCaptureConfigVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureTargetVO;
import com.genius.smartlight.vo.device.DeviceCamGlobalTrackingControlReqVO;
import com.genius.smartlight.vo.device.DeviceTrackingStatusRespVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.WebSocketPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixedPersonTrackingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeviceMapper deviceMapper;
    private DeviceSessionManager deviceSessionManager;
    private DeviceCamCaptureConfigService captureConfigService;
    private FixedPersonTrackingService service;

    @BeforeEach
    void setUp() {
        deviceMapper = mock(DeviceMapper.class);
        deviceSessionManager = mock(DeviceSessionManager.class);
        captureConfigService = mock(DeviceCamCaptureConfigService.class);
        service = new FixedPersonTrackingService(
                deviceMapper,
                mock(DurationRecordMapper.class),
                deviceSessionManager,
                mock(WebSocketPushService.class),
                captureConfigService,
                objectMapper
        );
    }

    @Test
    void startGlobal_armsAllLampsAndSendsThreeTargetsToCamera() throws Exception {
        DeviceDO cam = device("CAM-001", "cam", "192.168.1.10", 1L);
        DeviceDO lamp1 = device("LAMP-001", "lamp", "192.168.1.21", 1L);
        DeviceDO lamp2 = device("LAMP-002", "lamp", "192.168.1.22", 1L);
        DeviceDO lamp3 = device("LAMP-003", "lamp", "192.168.1.23", 1L);
        when(deviceMapper.selectOne(any())).thenReturn(cam, lamp1, lamp2, lamp3);
        when(captureConfigService.getForCurrentStore("CAM-001"))
                .thenReturn(config("CAM-001", lamp1, lamp2, lamp3));
        when(deviceSessionManager.isOnline(anyString())).thenReturn(true);
        when(deviceSessionManager.sendToDevice(anyString(), anyString())).thenReturn(true);

        DeviceCamGlobalTrackingControlReqVO request = new DeviceCamGlobalTrackingControlReqVO();
        request.setCamChipId("CAM-001");
        DeviceTrackingStatusRespVO result = service.startGlobal(request);

        assertEquals("global", result.getTrackingMode());
        assertEquals("armed", result.getTrackingStatus());
        assertEquals(List.of("LAMP-001", "LAMP-002", "LAMP-003"), result.getTargetChipIds());

        ArgumentCaptor<String> cameraCommand = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager).sendToDevice(eq("CAM-001"), cameraCommand.capture());
        JsonNode payload = objectMapper.readTree(cameraCommand.getValue());
        assertEquals("cameraStartTracking", payload.path("type").asText());
        assertEquals("global", payload.path("trackingMode").asText());
        assertEquals(3, payload.path("targets").size());
        assertEquals("LAMP-001", payload.path("targets").get(0).path("targetChipId").asText());
        assertEquals("192.168.1.21", payload.path("targets").get(0).path("lampIp").asText());
        assertEquals(FixedPersonTrackingService.TRACKING_UDP_PORT,
                payload.path("targets").get(0).path("udpPort").asInt());

        for (String lampChipId : result.getTargetChipIds()) {
            ArgumentCaptor<String> lampCommand = ArgumentCaptor.forClass(String.class);
            verify(deviceSessionManager).sendToDevice(eq(lampChipId), lampCommand.capture());
            JsonNode lampPayload = objectMapper.readTree(lampCommand.getValue());
            assertEquals("lampTrackingStart", lampPayload.path("type").asText());
            assertTrue(lampPayload.path("sessionId").isTextual());
        }
    }

    private DeviceCamCaptureConfigVO config(String camChipId, DeviceDO... lamps) {
        DeviceCamCaptureConfigVO config = new DeviceCamCaptureConfigVO();
        config.setCamChipId(camChipId);
        for (int index = 0; index < lamps.length; index++) {
            DeviceCamCaptureTargetVO target = new DeviceCamCaptureTargetVO();
            target.setIndex(index + 1);
            target.setLampChipId(lamps[index].getChipId());
            config.getTargets().add(target);
        }
        config.setConfigured(true);
        return config;
    }

    private DeviceDO device(String chipId, String type, String ip, Long storeId) {
        DeviceDO device = new DeviceDO();
        device.setChipId(chipId);
        device.setDeviceType(type);
        device.setIp(ip);
        device.setStoreId(storeId);
        return device;
    }
}
