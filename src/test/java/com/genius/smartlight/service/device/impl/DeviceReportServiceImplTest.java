package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.service.device.DeviceLastSeenService;
import com.genius.smartlight.service.device.OtaProgressStore;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.vo.device.DeviceStateReportReqVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.DeviceStateNotifier;
import com.genius.smartlight.websocket.WebSocketPushService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeviceReportServiceImplTest {

    @Test
    void reportStateReturnsBeforeBrowserWebSocketPushRuns() {
        DeviceMapper deviceMapper = mock(DeviceMapper.class);
        WebSocketPushService pushService = mock(WebSocketPushService.class);
        Queue<Runnable> queuedTasks = new ArrayDeque<>();
        DeviceStateNotifier notifier = new DeviceStateNotifier(pushService, queuedTasks::add);
        DeviceSessionManager deviceSessionManager = mock(DeviceSessionManager.class);
        DeviceLastSeenService deviceLastSeenService = mock(DeviceLastSeenService.class);

        DeviceDO device = new DeviceDO();
        device.setId(1L);
        device.setChipId("LAMP-B3F738");
        device.setStoreId(7L);
        device.setOtaStatus("idle");
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);

        DeviceReportServiceImpl service = new DeviceReportServiceImpl(
                deviceMapper,
                notifier,
                deviceSessionManager,
                new OtaProgressStore(),
                mock(ObjectMapper.class),
                deviceLastSeenService
        );
        DeviceStateReportReqVO request = new DeviceStateReportReqVO();
        request.setChipId("LAMP-B3F738");

        service.reportState(request);

        verify(deviceMapper).updateById(device);
        verifyNoInteractions(pushService);
        assertThat(queuedTasks).hasSize(1);

        queuedTasks.remove().run();

        verify(pushService).pushState(any(DeviceRespVO.class));
    }
}
