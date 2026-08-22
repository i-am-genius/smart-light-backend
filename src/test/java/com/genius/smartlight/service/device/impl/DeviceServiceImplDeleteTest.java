package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.LoginUser;
import com.genius.smartlight.service.device.OtaProgressStore;
import com.genius.smartlight.service.lighteffect.LightEffectService;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.WebSocketPushService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceServiceImplDeleteTest {

    private static final long USER_ID = 7L;
    private static final long STORE_ID = 3L;
    private static final long DEVICE_ID = 11L;
    private static final String CHIP_ID = "CAM-CAPTURE-DELETE";

    private DeviceMapper deviceMapper;
    private DeviceSessionManager deviceSessionManager;
    private DeviceServiceImpl service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(USER_ID, "tester"), null)
        );

        deviceMapper = mock(DeviceMapper.class);
        StoreMapper storeMapper = mock(StoreMapper.class);
        deviceSessionManager = mock(DeviceSessionManager.class);
        WebSocketPushService pushService = mock(WebSocketPushService.class);
        service = new DeviceServiceImpl(
                pushService,
                deviceSessionManager,
                deviceMapper,
                storeMapper,
                new ObjectMapper(),
                new OtaProgressStore(),
                mock(LightEffectService.class)
        );

        StoreDO store = new StoreDO();
        store.setId(STORE_ID);
        store.setUserId(USER_ID);
        when(storeMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(store);

        DeviceDO device = new DeviceDO();
        device.setId(DEVICE_ID);
        device.setChipId(CHIP_ID);
        device.setDeviceType("cam_capture");
        device.setStoreId(STORE_ID);
        when(deviceMapper.selectById(DEVICE_ID)).thenReturn(device);
        when(deviceSessionManager.normalizeChipId(CHIP_ID)).thenReturn(CHIP_ID);
        when(deviceSessionManager.isOnline(CHIP_ID)).thenReturn(false);
        when(deviceSessionManager.sendToDevice(eq(CHIP_ID), any(String.class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deleteStillAttemptsResumeCommandWhenLastSeenIsStale() {
        service.deleteDevice(DEVICE_ID);

        verify(deviceSessionManager).sendToDevice(
                eq(CHIP_ID),
                argThat(payload -> payload.contains("\"type\":\"command\"")
                        && payload.contains("\"cmd\":\"resume_broadcast\""))
        );
        verify(deviceMapper).deleteById(DEVICE_ID);
    }
}
