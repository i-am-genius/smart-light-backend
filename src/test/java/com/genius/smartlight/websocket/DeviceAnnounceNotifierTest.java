package com.genius.smartlight.websocket;

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

class DeviceAnnounceNotifierTest {

    @Test
    void pushAsyncQueuesBrowserBroadcastOutsideRequestThread() {
        WebSocketPushService pushService = mock(WebSocketPushService.class);
        Queue<Runnable> queuedTasks = new ArrayDeque<>();
        Executor executor = queuedTasks::add;
        DeviceAnnounceNotifier notifier = new DeviceAnnounceNotifier(pushService, executor);

        notifier.pushAsync("LAMP-B3F738", "192.168.1.20", "lamp", true, 7L);

        verifyNoInteractions(pushService);
        assertThat(queuedTasks).hasSize(1);

        queuedTasks.remove().run();

        verify(pushService).pushAnnounce(
                "LAMP-B3F738",
                "192.168.1.20",
                "lamp",
                true,
                7L
        );
    }

    @Test
    void rejectedBrowserBroadcastDoesNotFailAnnounceRequest() {
        WebSocketPushService pushService = mock(WebSocketPushService.class);
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("queue full");
        };
        DeviceAnnounceNotifier notifier = new DeviceAnnounceNotifier(pushService, rejectingExecutor);

        assertThatCode(() -> notifier.pushAsync(
                "LAMP-B3F738",
                "192.168.1.20",
                "lamp",
                true,
                7L
        )).doesNotThrowAnyException();

        verifyNoInteractions(pushService);
    }
}
