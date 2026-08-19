package com.genius.smartlight.service.device.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.websocket.DeviceSessionManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptureLightingServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsDedicatedCaptureLightingMessageWithoutMutatingLampState() throws Exception {
        Fixture fixture = fixture();

        String sessionId = fixture.service.startStandard("LAMP-1", "PHONE:test-1");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(fixture.sessions).sendToDevice(eq("LAMP-1"), payloadCaptor.capture());
        var payload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(sessionId).isEqualTo("PHONE:test-1");
        assertThat(payload.path("type").asText()).isEqualTo("captureLighting");
        assertThat(payload.path("active").asBoolean()).isTrue();
        assertThat(payload.path("brightness").asInt()).isEqualTo(72);
        assertThat(payload.path("temp").asInt()).isEqualTo(4300);
        assertThat(fixture.lamp.getAutoMode()).isTrue();
        assertThat(fixture.lamp.getBrightness()).isEqualTo(55);
        assertThat(fixture.lamp.getTemp()).isEqualTo(3500);
    }

    @Test
    void releasingOneLeaseDoesNotStopAnotherActiveCapture() throws Exception {
        Fixture fixture = fixture();

        fixture.service.startStandard("LAMP-1", "CAMERA:task-a");
        fixture.service.startStandard("LAMP-1", "CAMERA:task-b");
        fixture.service.stop("LAMP-1", "CAMERA:task-a");

        ArgumentCaptor<String> beforeFinalRelease = ArgumentCaptor.forClass(String.class);
        verify(fixture.sessions, times(2)).sendToDevice(eq("LAMP-1"), beforeFinalRelease.capture());
        assertThat(beforeFinalRelease.getAllValues())
                .allSatisfy(json -> {
                    try {
                        assertThat(objectMapper.readTree(json).path("active").asBoolean()).isTrue();
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                });

        fixture.service.stop("LAMP-1", "CAMERA:task-b");

        ArgumentCaptor<String> allPayloads = ArgumentCaptor.forClass(String.class);
        verify(fixture.sessions, times(3)).sendToDevice(eq("LAMP-1"), allPayloads.capture());
        List<String> payloads = allPayloads.getAllValues();
        assertThat(objectMapper.readTree(payloads.get(2)).path("active").asBoolean()).isFalse();
    }

    private Fixture fixture() {
        DeviceMapper deviceMapper = mock(DeviceMapper.class);
        StoreMapper storeMapper = mock(StoreMapper.class);
        DeviceSessionManager sessions = mock(DeviceSessionManager.class);
        CaptureLightingServiceImpl service = new CaptureLightingServiceImpl(
                deviceMapper,
                storeMapper,
                sessions,
                objectMapper
        );
        ReflectionTestUtils.setField(service, "standardBrightness", 72);
        ReflectionTestUtils.setField(service, "standardTemp", 4300);
        ReflectionTestUtils.setField(service, "ttlMs", 12000L);
        ReflectionTestUtils.setField(service, "settleMs", 300L);

        DeviceDO lamp = new DeviceDO();
        lamp.setId(1L);
        lamp.setChipId("LAMP-1");
        lamp.setDeviceType("lamp");
        lamp.setAutoMode(true);
        lamp.setBrightness(55);
        lamp.setTemp(3500);
        when(deviceMapper.selectOne(any())).thenReturn(lamp);
        when(sessions.isOnline("LAMP-1")).thenReturn(true);
        when(sessions.sendToDevice(eq("LAMP-1"), any(String.class))).thenReturn(true);
        return new Fixture(service, sessions, lamp);
    }

    private record Fixture(
            CaptureLightingServiceImpl service,
            DeviceSessionManager sessions,
            DeviceDO lamp) {
    }
}
