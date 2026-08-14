package com.genius.smartlight.service.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.websocket.DeviceSessionManager;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceFleetLifecycleServiceTest {

    @Test
    void startsOneFullScanOnlyAfterEveryParticipatingDeviceIsOnline() {
        DeviceMapper mapper = mock(DeviceMapper.class);
        DeviceSessionManager sessions = mock(DeviceSessionManager.class);
        DeviceCamService camService = mock(DeviceCamService.class);
        DeviceFleetLifecycleService service = new DeviceFleetLifecycleService(mapper, sessions, camService);
        List<DeviceDO> devices = List.of(
                device("CAM-1", "cam"),
                device("LAMP-1", "lamp"),
                device("LAMP-2", "lamp")
        );
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(devices);
        Set<String> online = new HashSet<>();
        when(sessions.isOnline(any())).thenAnswer(invocation -> online.contains(invocation.getArgument(0)));

        service.onOnlineStatusChanged(1L);
        online.add("CAM-1");
        service.onOnlineStatusChanged(1L);
        online.add("LAMP-1");
        service.onOnlineStatusChanged(1L);
        online.add("LAMP-2");
        service.onOnlineStatusChanged(1L);
        service.onOnlineStatusChanged(1L);

        verify(camService).resetAutomaticGarmentDetection(1L);
        verify(camService).startAutomaticGarmentDetection(1L);

        online.clear();
        service.onOnlineStatusChanged(1L);
        online.addAll(List.of("CAM-1", "LAMP-1", "LAMP-2"));
        service.onOnlineStatusChanged(1L);

        verify(camService, times(2)).resetAutomaticGarmentDetection(1L);
        verify(camService, times(2)).startAutomaticGarmentDetection(1L);
    }

    private static DeviceDO device(String chipId, String type) {
        DeviceDO device = new DeviceDO();
        device.setChipId(chipId);
        device.setDeviceType(type);
        device.setStoreId(1L);
        return device;
    }
}
