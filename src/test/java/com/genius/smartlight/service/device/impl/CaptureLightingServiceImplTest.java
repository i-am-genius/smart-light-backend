package com.genius.smartlight.service.device.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.websocket.DeviceSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaptureLightingServiceImplTest {

    @Test
    void sendsDedicatedCaptureLightingMessageWithoutMutatingLampState() {
        DeviceMapper deviceMapper = mock(DeviceMapper.class);
        StoreMapper storeMapper = mock(StoreMapper.class);
        DeviceSessionManager sessions = mock(DeviceSessionManager.class);
        CaptureLightingServiceImpl service = new CaptureLightingServiceImpl(
                deviceMapper,
                storeMapper,
                sessions,
                new ObjectMapper()
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

        service.startStandard("LAMP-1");

        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sessions).sendToDevice(eq("LAMP-1"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"type\":\"captureLighting\"");
        assertThat(payloadCaptor.getValue()).contains("\"active\":true");
        assertThat(payloadCaptor.getValue()).contains("\"brightness\":72");
        assertThat(payloadCaptor.getValue()).contains("\"temp\":4300");
        assertThat(lamp.getAutoMode()).isTrue();
        assertThat(lamp.getBrightness()).isEqualTo(55);
        assertThat(lamp.getTemp()).isEqualTo(3500);
    }
}
