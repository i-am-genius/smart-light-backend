package com.genius.smartlight.schedule;

import com.genius.smartlight.service.device.DeviceOnlinePushService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DeviceOnlineStatusScheduler {

    private final DeviceOnlinePushService deviceOnlinePushService;

    /**
     * 每 10 秒扫描一次，检测超时离线并推送
     */
    @Scheduled(fixedDelay = 10000)
    public void checkOnlineStatus() {
        deviceOnlinePushService.scanAndPushOfflineChanges();
    }
}