package com.genius.smartlight.service.device.impl;

import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.service.device.GarmentSourceResultService;
import com.genius.smartlight.vo.device.DeviceSaveReqVO;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GarmentAimEnableRestoreAspectTest {

    @Mock
    private DeviceMapper deviceMapper;
    @Mock
    private GarmentSourceResultService garmentSourceResultService;
    @Mock
    private ProceedingJoinPoint joinPoint;

    private GarmentAimEnableRestoreAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new GarmentAimEnableRestoreAspect(deviceMapper, garmentSourceResultService);
    }

    @Test
    void restoresLatestSourceOnlyAfterOffToOnUpdate() throws Throwable {
        DeviceDO before = lamp(false);
        DeviceDO after = lamp(true);
        DeviceSaveReqVO request = request(true);
        when(deviceMapper.selectById(1L)).thenReturn(before, after);
        when(joinPoint.proceed()).thenReturn(null);

        aspect.restoreLatestSourceAfterEnable(joinPoint, 1L, request, false);

        verify(joinPoint).proceed();
        verify(garmentSourceResultService).pushLatestResult("LAMP-1");
    }

    @Test
    void doesNotRestoreWhenFollowWasAlreadyEnabled() throws Throwable {
        DeviceDO before = lamp(true);
        DeviceSaveReqVO request = request(true);
        when(deviceMapper.selectById(1L)).thenReturn(before);
        when(joinPoint.proceed()).thenReturn(null);

        aspect.restoreLatestSourceAfterEnable(joinPoint, 1L, request, false);

        verify(garmentSourceResultService, never()).pushLatestResult("LAMP-1");
    }

    @Test
    void doesNotRestoreWhenRequestKeepsFollowDisabled() throws Throwable {
        DeviceDO before = lamp(false);
        DeviceSaveReqVO request = request(false);
        when(deviceMapper.selectById(1L)).thenReturn(before);
        when(joinPoint.proceed()).thenReturn(null);

        aspect.restoreLatestSourceAfterEnable(joinPoint, 1L, request, false);

        verify(garmentSourceResultService, never()).pushLatestResult("LAMP-1");
    }

    @Test
    void doesNotRestoreWhenUnderlyingUpdateFails() throws Throwable {
        DeviceDO before = lamp(false);
        DeviceSaveReqVO request = request(true);
        when(deviceMapper.selectById(1L)).thenReturn(before);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("update failed"));

        assertThatThrownBy(() -> aspect.restoreLatestSourceAfterEnable(joinPoint, 1L, request, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("update failed");

        verify(garmentSourceResultService, never()).pushLatestResult("LAMP-1");
    }

    private static DeviceDO lamp(boolean enabled) {
        DeviceDO device = new DeviceDO();
        device.setId(1L);
        device.setChipId("LAMP-1");
        device.setGarmentAimEnabled(enabled);
        return device;
    }

    private static DeviceSaveReqVO request(boolean enabled) {
        DeviceSaveReqVO request = new DeviceSaveReqVO();
        request.setGarmentAimEnabled(enabled);
        return request;
    }
}
