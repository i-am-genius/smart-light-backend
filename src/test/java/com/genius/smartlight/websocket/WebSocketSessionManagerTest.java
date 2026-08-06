package com.genius.smartlight.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketSessionManagerTest {

    private WebSocketSessionManager manager;

    @BeforeEach
    void setUp() {
        manager = new WebSocketSessionManager();
    }

    @Test
    void binaryBroadcastOnlyTargetsCapableSessionsInTheSameStore() throws Exception {
        WebSocketSession storeOneCapable = session("s1");
        WebSocketSession storeOneLegacy = session("s2");
        WebSocketSession storeTwoCapable = session("s3");
        manager.addSession(storeOneCapable);
        manager.addSession(storeOneLegacy);
        manager.addSession(storeTwoCapable);
        manager.registerStore("s1", 1L);
        manager.registerStore("s2", 1L);
        manager.registerStore("s3", 2L);
        assertThat(manager.enableFabricImageBinary("s1", 1)).isTrue();
        assertThat(manager.enableFabricImageBinary("s3", 1)).isTrue();

        assertThat(manager.broadcastBinaryToCapableStore(1L, new byte[]{1, 2, 3}))
                .isEqualTo(1);

        verify(storeOneCapable).sendMessage(argThat(BinaryMessage.class::isInstance));
        verify(storeOneLegacy, never()).sendMessage(any(WebSocketMessage.class));
        verify(storeTwoCapable, never()).sendMessage(any(WebSocketMessage.class));
    }

    @Test
    void binarySendRequiresSupportedVersionAndRegisteredOpenSession() {
        WebSocketSession session = session("s1");
        manager.addSession(session);

        assertThat(manager.enableFabricImageBinary("s1", 2)).isFalse();
        assertThat(manager.sendBinary("s1", new byte[]{1})).isFalse();
        assertThat(manager.enableFabricImageBinary("missing", 1)).isFalse();
        assertThat(manager.enableFabricImageBinary("s1", 1)).isTrue();
        assertThat(manager.sendBinary("s1", new byte[]{1})).isTrue();
    }

    @Test
    void removingSessionAlsoRemovesBinaryCapability() {
        WebSocketSession session = session("s1");
        manager.addSession(session);
        assertThat(manager.enableFabricImageBinary("s1", 1)).isTrue();

        manager.removeSession(session);

        assertThat(manager.isFabricImageBinaryEnabled("s1")).isFalse();
        assertThat(manager.sendBinary("s1", new byte[]{1})).isFalse();
    }

    @Test
    void textAndBinarySendsSharePerSessionBackpressure() throws Exception {
        WebSocketSession session = session("s1");
        CountDownLatch firstDelegateSendEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstDelegateSend = new CountDownLatch(1);
        AtomicInteger delegateCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (delegateCalls.incrementAndGet() == 1) {
                firstDelegateSendEntered.countDown();
                releaseFirstDelegateSend.await(5, TimeUnit.SECONDS);
            }
            return null;
        }).when(session).sendMessage(any(WebSocketMessage.class));
        manager.addSession(session);
        assertThat(manager.enableFabricImageBinary("s1", 1)).isTrue();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> textSend = executor.submit(() -> manager.send(session, "text"));
            assertThat(firstDelegateSendEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> binarySend = executor.submit(
                    () -> manager.sendBinary("s1", new byte[]{1})
            );

            assertThatThrownBy(() -> binarySend.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseFirstDelegateSend.countDown();
            textSend.get(2, TimeUnit.SECONDS);
            assertThat(binarySend.get(2, TimeUnit.SECONDS)).isTrue();
            assertThat(delegateCalls).hasValue(2);
        } finally {
            releaseFirstDelegateSend.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void blockedDelegateSendIsTimedOutAndSessionIsRemoved() throws Exception {
        manager = new WebSocketSessionManager(100);
        WebSocketSession session = session("s1");
        CountDownLatch delegateSendEntered = new CountDownLatch(1);
        CountDownLatch releaseDelegateSend = new CountDownLatch(1);
        doAnswer(invocation -> {
            delegateSendEntered.countDown();
            releaseDelegateSend.await(5, TimeUnit.SECONDS);
            return null;
        }).when(session).sendMessage(any(WebSocketMessage.class));
        manager.addSession(session);
        assertThat(manager.enableFabricImageBinary("s1", 1)).isTrue();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> blockedTextSend = executor.submit(() -> manager.send(session, "text"));
            assertThat(delegateSendEntered.await(2, TimeUnit.SECONDS)).isTrue();

            verify(session, timeout(2_000)).close();
            assertThat(manager.isFabricImageBinaryEnabled("s1")).isFalse();
            assertThat(manager.getSessionCount()).isZero();
            assertThat(manager.sendBinary("s1", new byte[]{1})).isFalse();

            releaseDelegateSend.countDown();
            blockedTextSend.get(2, TimeUnit.SECONDS);
        } finally {
            releaseDelegateSend.countDown();
            executor.shutdownNow();
        }
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
