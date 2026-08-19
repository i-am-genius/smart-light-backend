package com.genius.smartlight.service.device.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.vo.ai.GarmentResultSnapshot;
import com.genius.smartlight.websocket.WebSocketPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GarmentSourceResultServiceImplTest {

    @Mock
    private DeviceMapper deviceMapper;

    @Mock
    private WebSocketPushService webSocketPushService;

    private GarmentSourceResultServiceImpl service;
    private DeviceDO lamp;

    @BeforeEach
    void setUp() {
        service = new GarmentSourceResultServiceImpl(deviceMapper, new ObjectMapper(), webSocketPushService);
        lamp = new DeviceDO();
        lamp.setId(1L);
        lamp.setChipId("LAMP-1");
        lamp.setGarmentAimEnabled(true);
        lamp.setGarmentResultJson(phoneJson());
        when(deviceMapper.selectOne(any())).thenReturn(lamp);
        lenient().when(deviceMapper.updateById(any(DeviceDO.class))).thenReturn(1);
    }

    @Test
    void storesPhoneResultAndMarksItLatest() {
        String originalLatest = lamp.getGarmentResultJson();

        service.saveLatestResult("LAMP-1", "PHONE");

        assertThat(lamp.getGarmentResultJson()).isEqualTo(originalLatest);
        assertThat(lamp.getGarmentSourceResultJson()).contains("\"latestSourceKey\":\"PHONE\"");
        assertThat(lamp.getGarmentSourceResultJson()).contains("recognizedAt");
        verify(webSocketPushService).pushGarmentAimToDevice(
                eq("LAMP-1"),
                any(GarmentResultSnapshot.class),
                eq("PHONE"),
                eq(true)
        );
    }

    @Test
    void keepsPhoneAndCameraSlotsIndependentAndCameraBecomesLatest() {
        service.saveLatestResult("LAMP-1", "PHONE");
        lamp.setGarmentResultJson(cameraJson());

        service.saveLatestResult("LAMP-1", "CAMERA:CAM-1");

        assertThat(lamp.getGarmentSourceResultJson()).contains("PHONE");
        assertThat(lamp.getGarmentSourceResultJson()).contains("CAMERA:CAM-1");
        assertThat(lamp.getGarmentSourceResultJson()).contains("12:00:00");
        assertThat(lamp.getGarmentSourceResultJson()).contains("12:01:00");
        assertThat(lamp.getGarmentSourceResultJson()).contains("\"latestSourceKey\":\"CAMERA:CAM-1\"");
    }

    @Test
    void pushesStoredSnapshotForLatestSourceWhenFollowIsEnabledLater() {
        lamp.setGarmentSourceResultJson(
                "{\"version\":1,\"latestSourceKey\":\"CAMERA:CAM-1\",\"sources\":{"
                        + "\"PHONE\":" + jsonString(phoneJson()) + ","
                        + "\"CAMERA:CAM-1\":" + jsonString(cameraJson()) + "}}"
        );
        lamp.setGarmentResultJson(cameraJson());

        service.pushLatestResult("LAMP-1");

        verify(webSocketPushService).pushGarmentAimToDevice(
                eq("LAMP-1"),
                any(GarmentResultSnapshot.class),
                eq("CAMERA:CAM-1"),
                eq(true)
        );
    }

    @Test
    void infersLegacyLatestSourceOnlyFromExactUniqueSnapshotMatch() {
        lamp.setGarmentSourceResultJson(
                "{\"version\":1,\"sources\":{"
                        + "\"PHONE\":" + jsonString(phoneJson()) + ","
                        + "\"CAMERA:CAM-1\":" + jsonString(cameraJson()) + "}}"
        );
        lamp.setGarmentResultJson(cameraJson());

        service.pushLatestResult("LAMP-1");

        verify(webSocketPushService).pushGarmentAimToDevice(
                eq("LAMP-1"),
                any(GarmentResultSnapshot.class),
                eq("CAMERA:CAM-1"),
                eq(true)
        );
    }

    @Test
    void doesNotGuessWhenLegacySnapshotMatchesMoreThanOneSource() {
        lamp.setGarmentSourceResultJson(
                "{\"version\":1,\"sources\":{"
                        + "\"PHONE\":" + jsonString(phoneJson()) + ","
                        + "\"CAMERA:CAM-1\":" + jsonString(phoneJson()) + "}}"
        );
        lamp.setGarmentResultJson(phoneJson());

        service.pushLatestResult("LAMP-1");

        verify(webSocketPushService, never()).pushGarmentAimToDevice(
                any(), any(), any(), anyBoolean()
        );
    }

    private static String phoneJson() {
        return "{\"resultVersion\":2,\"clothDetected\":true,\"imageWidth\":100,\"imageHeight\":100,\"recognizedAt\":\"2026-08-19T12:00:00\",\"garments\":[]}";
    }

    private static String cameraJson() {
        return "{\"resultVersion\":2,\"clothDetected\":true,\"imageWidth\":200,\"imageHeight\":100,\"recognizedAt\":\"2026-08-19T12:01:00\",\"garments\":[]}";
    }

    private static String jsonString(String value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
