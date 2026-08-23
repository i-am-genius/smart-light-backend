package com.genius.smartlight.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceSessionManagerCollisionGuardTest {

    @Test
    void collisionParkDoesNotSucceedUntilLampAcknowledgesAccepted() throws Exception {
        DeviceSessionManager manager = new DeviceSessionManager();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);

        CountDownLatch parkSent = new CountDownLatch(1);
        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            if (message.getPayload().contains("\"action\":\"park\"")) {
                parkSent.countDown();
            }
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        manager.registerDevice("LAMP-ACK", session);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> sendResult = executor.submit(() -> manager.sendToDevice(
                    "LAMP-ACK",
                    "{\"type\":\"lampCollisionGuard\",\"action\":\"park\",\"guardId\":\"guard-1\",\"pan\":0,\"tilt\":0}"
            ));

            assertThat(parkSent.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50L);
            assertThat(sendResult.isDone()).isFalse();

            manager.confirmCollisionGuard("LAMP-ACK", "guard-1", "accepted");

            assertThat(sendResult.get(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedCollisionParkFailsAndBestEffortReleasesLamp() throws Exception {
        DeviceSessionManager manager = new DeviceSessionManager();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-2");
        when(session.isOpen()).thenReturn(true);

        CountDownLatch parkSent = new CountDownLatch(1);
        List<String> sentPayloads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            sentPayloads.add(message.getPayload());
            if (message.getPayload().contains("\"action\":\"park\"")) {
                parkSent.countDown();
            }
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        manager.registerDevice("LAMP-REJECT", session);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> sendResult = executor.submit(() -> manager.sendToDevice(
                    "LAMP-REJECT",
                    "{\"type\":\"lampCollisionGuard\",\"action\":\"park\",\"guardId\":\"guard-2\"}"
            ));

            assertThat(parkSent.await(1, TimeUnit.SECONDS)).isTrue();
            manager.confirmCollisionGuard("LAMP-REJECT", "guard-2", "rejected");

            assertThat(sendResult.get(1, TimeUnit.SECONDS)).isFalse();
            assertThat(sentPayloads).anyMatch(payload ->
                    payload.contains("\"type\":\"lampCollisionGuard\"")
                            && payload.contains("\"action\":\"release\"")
                            && payload.contains("\"guardId\":\"guard-2\""));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void nonCollisionCommandsRemainImmediate() throws Exception {
        DeviceSessionManager manager = new DeviceSessionManager();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-3");
        when(session.isOpen()).thenReturn(true);
        manager.registerDevice("LAMP-NORMAL", session);

        long startedAt = System.nanoTime();
        boolean sent = manager.sendToDevice(
                "LAMP-NORMAL",
                "{\"type\":\"arm_position\",\"slider\":320}"
        );
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(sent).isTrue();
        assertThat(elapsedMs).isLessThan(500L);
    }
}
