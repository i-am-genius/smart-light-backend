package com.genius.smartlight.task;

import com.genius.smartlight.service.device.DeviceLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceLogCleanupTask {

    private final DeviceLogService deviceLogService;

    /**
     * 每天凌晨 3 点清理过期日志文件
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpiredLogs() {
        log.info("Starting device log cleanup task");
        try {
            deviceLogService.cleanupOldLogs();
            log.info("Device log cleanup task completed");
        } catch (Exception e) {
            log.error("Device log cleanup task failed", e);
        }
    }
}
