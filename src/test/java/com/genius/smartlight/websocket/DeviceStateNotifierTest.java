package com.genius.smartlight.websocket;

import com.genius.smartlight.vo.device.DeviceRespVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DeviceStateNotifierTest {

    @Test
    void pushAsyncQueuesBrowserBroadcastOutsideRequestThread() {
        WebSocketPushService pushService = mock(WebSocketPushService.class);
        Queue<Runnable> queuedTasks = new ArrayDeque<>();
        Executor executor = queuedTasks::add;
        DeviceStateNotifier notifier = new DeviceStateNotifier(pushService, executor);
        DeviceRespVO state = state("LAMP-B3F738", 7L);

        notifier.pushAsync(state);

        verifyNoInteractions(pushService);
        assertThat(queuedTasks).hasSize(1);

        queuedTasks.remove().run();

        verify(pushService).pushState(state);
    }

    @Test
    void rejectedBrowserBroadcastDoesNotFailStateReportRequest() {
        WebSocketPushService pushService = mock(WebSocketPushService.class);
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("queue full");
        };
        DeviceStateNotifier notifier = new DeviceStateNotifier(pushService, rejectingExecutor);
        DeviceRespVO state = state("LAMP-B3F738", 7L);

        assertThatCode(() -> notifier.pushAsync(state)).doesNotThrowAnyException();

        verifyNoInteractions(pushService);
    }

    private DeviceRespVO state(String chipId, Long storeId) {
        DeviceRespVO state = new DeviceRespVO();
        state.setChipId(chipId);
        state.setStoreId(storeId);
        return state;
    }
}
