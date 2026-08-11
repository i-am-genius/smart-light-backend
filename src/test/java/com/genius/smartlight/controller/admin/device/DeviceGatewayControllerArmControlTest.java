package com.genius.smartlight.controller.admin.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.device.DeviceControlService;
import com.genius.smartlight.vo.device.DeviceArmControlReqVO;
import com.genius.smartlight.websocket.DeviceAnnounceNotifier;
import com.genius.smartlight.websocket.DeviceSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceGatewayControllerArmControlTest {

    private static final String CHIP_ID = "LAMP-001";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeviceGatewayController controller;
    private StoreMapper storeMapper;
    private DeviceMapper deviceMapper;
    private DeviceSessionManager deviceSessionManager;

    @BeforeEach
    void setUp() {
        deviceMapper = mock(DeviceMapper.class);
        storeMapper = mock(StoreMapper.class);
        deviceSessionManager = mock(DeviceSessionManager.class);

        StoreDO store = new StoreDO();
        store.setId(7L);
        store.setUserId(42L);
        when(storeMapper.selectOne(any())).thenReturn(store);

        DeviceDO device = new DeviceDO();
        device.setChipId(CHIP_ID);
        device.setDeviceType("lamp");
        device.setStoreId(7L);
        when(deviceMapper.selectOne(any())).thenReturn(device);
        when(deviceSessionManager.isOnline(CHIP_ID)).thenReturn(true);
        when(deviceSessionManager.sendToDevice(eq(CHIP_ID), any())).thenReturn(true);

        controller = new DeviceGatewayController(
                deviceMapper,
                storeMapper,
                deviceSessionManager,
                mock(DeviceAnnounceNotifier.class),
                objectMapper,
                mock(DeviceControlService.class)
        );
    }

    @Test
    void armPosition_acceptsPanAtBothNinetyDegreeBoundaries() throws Exception {
        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(42L);

            controller.armControl(CHIP_ID, positionRequest(-90f));
            controller.armControl(CHIP_ID, positionRequest(90f));
        }

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(deviceSessionManager, times(2)).sendToDevice(eq(CHIP_ID), payloadCaptor.capture());

        JsonNode negativeBoundary = objectMapper.readTree(payloadCaptor.getAllValues().get(0));
        JsonNode positiveBoundary = objectMapper.readTree(payloadCaptor.getAllValues().get(1));
        assertThat(negativeBoundary.path("pan").floatValue()).isEqualTo(-90f);
        assertThat(positiveBoundary.path("pan").floatValue()).isEqualTo(90f);
    }

    @Test
    void armPosition_rejectsPanOutsideNinetyDegreeRange() {
        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(42L);

            assertThatThrownBy(() -> controller.armControl(CHIP_ID, positionRequest(90.1f)))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("pan range must be -90.0 to 90.0 degrees");
        }
    }

    private DeviceArmControlReqVO positionRequest(float pan) {
        DeviceArmControlReqVO request = new DeviceArmControlReqVO();
        request.setType("arm_position");
        request.setPan(pan);
        return request;
    }
}
