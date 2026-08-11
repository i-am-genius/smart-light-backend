package com.genius.smartlight.websocket;

import com.genius.smartlight.vo.device.DeviceRespVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Component
public class DeviceStateNotifier {

    private final WebSocketPushService pushService;
    private final Executor executor;

    public DeviceStateNotifier(
            WebSocketPushService pushService,
            @Qualifier("deviceStatePushExecutor") Executor executor
    ) {
        this.pushService = pushService;
        this.executor = executor;
    }

    public void pushAsync(DeviceRespVO state) {
        try {
            executor.execute(() -> pushService.pushState(state));
        } catch (RejectedExecutionException e) {
            log.warn("[state-report] event=browser_push_rejected, chipId={}, storeId={}",
                    state.getChipId(), state.getStoreId());
        }
    }
}
