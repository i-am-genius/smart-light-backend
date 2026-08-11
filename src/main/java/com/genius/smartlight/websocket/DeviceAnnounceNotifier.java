package com.genius.smartlight.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
public class DeviceAnnounceNotifier {

    private final WebSocketPushService pushService;
    private final Executor executor;

    public DeviceAnnounceNotifier(
            WebSocketPushService pushService,
            @Qualifier("deviceAnnouncePushExecutor") Executor executor
    ) {
        this.pushService = pushService;
        this.executor = executor;
    }

    public void pushAsync(String chipId,
                          String ip,
                          String deviceType,
                          Boolean added,
                          Long storeId) {
        try {
            executor.execute(() -> pushService.pushAnnounce(
                    chipId,
                    ip,
                    deviceType,
                    added,
                    storeId
            ));
        } catch (RejectedExecutionException e) {
            log.warn("[announce] event=browser_push_rejected, chipId={}, storeId={}",
                    chipId, storeId);
        }
    }
}
