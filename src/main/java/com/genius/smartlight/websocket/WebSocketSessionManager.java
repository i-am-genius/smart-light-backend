package com.genius.smartlight.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class WebSocketSessionManager {

    private static final int DEFAULT_SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;
    private static final int FABRIC_IMAGE_PROTOCOL_VERSION = 1;
    private static final ScheduledThreadPoolExecutor SEND_TIMEOUT_SCHEDULER =
            createSendTimeoutScheduler();

    private final int sendTimeLimitMs;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionStoreMap = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionUserMap = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> sessionSendLocks = new ConcurrentHashMap<>();
    private final Set<String> fabricImageBinarySessions = ConcurrentHashMap.newKeySet();

    public WebSocketSessionManager() {
        this(DEFAULT_SEND_TIME_LIMIT_MS);
    }

    WebSocketSessionManager(int sendTimeLimitMs) {
        if (sendTimeLimitMs <= 0) {
            throw new IllegalArgumentException("sendTimeLimitMs 必须大于 0");
        }
        this.sendTimeLimitMs = sendTimeLimitMs;
    }

    public void addSession(WebSocketSession session) {
        WebSocketSession managedSession = new ConcurrentWebSocketSessionDecorator(
                session,
                sendTimeLimitMs,
                BUFFER_SIZE_LIMIT_BYTES
        );
        sessions.put(session.getId(), managedSession);
        sessionSendLocks.putIfAbsent(session.getId(), new ReentrantLock());
        // connection logged by AppWebSocketHandler
    }

    public void registerStore(String sessionId, Long storeId) {
        if (storeId != null) {
            sessionStoreMap.put(sessionId, storeId);
        }
    }

    public void registerUser(String sessionId, Long userId) {
        if (userId != null) {
            sessionUserMap.put(sessionId, userId);
        }
    }

    public void removeSession(WebSocketSession session) {
        removeSessionById(session.getId());
        // disconnection logged by AppWebSocketHandler
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public boolean enableFabricImageBinary(String sessionId, int version) {
        if (version != FABRIC_IMAGE_PROTOCOL_VERSION || !sessions.containsKey(sessionId)) {
            return false;
        }
        return fabricImageBinarySessions.add(sessionId);
    }

    public boolean isFabricImageBinaryEnabled(String sessionId) {
        return fabricImageBinarySessions.contains(sessionId);
    }

    public boolean sendBinary(String sessionId, byte[] payload) {
        if (payload == null || payload.length == 0
                || !fabricImageBinarySessions.contains(sessionId)) {
            return false;
        }
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        ReentrantLock sendLock = acquireSendLock(sessionId, session);
        if (sendLock == null) {
            return false;
        }
        try {
            WebSocketSession current = sessions.get(sessionId);
            if (current == null || !current.isOpen()
                    || !fabricImageBinarySessions.contains(sessionId)) {
                return false;
            }
            AtomicBoolean finished = new AtomicBoolean();
            AtomicBoolean timedOut = new AtomicBoolean();
            ScheduledFuture<?> timeout = scheduleSendTimeout(
                    sessionId, current, finished, timedOut
            );
            try {
                current.sendMessage(new BinaryMessage(payload));
                return finishBeforeTimeout(finished, timeout) && !timedOut.get();
            } catch (Exception e) {
                removeSessionById(sessionId);
                closeQuietly(current);
                log.warn("[ws] event=binary_send_failed, sessionId={}, errorType={}",
                        sessionId, e.getClass().getSimpleName());
                return false;
            } finally {
                finishBeforeTimeout(finished, timeout);
            }
        } finally {
            sendLock.unlock();
        }
    }

    public int broadcastBinaryToCapableStore(Long storeId, byte[] payload) {
        if (storeId == null || payload == null || payload.length == 0) {
            return 0;
        }
        int sent = 0;
        for (String sessionId : fabricImageBinarySessions) {
            if (storeId.equals(sessionStoreMap.get(sessionId))
                    && sendBinary(sessionId, payload)) {
                sent++;
            }
        }
        return sent;
    }

    public void send(WebSocketSession session, String payload) {
        if (session == null || !session.isOpen()) {
            return;
        }
        WebSocketSession targetSession = sessions.getOrDefault(session.getId(), session);
        if (!targetSession.isOpen()) {
            removeSessionById(session.getId());
            return;
        }
        ReentrantLock sendLock = sessionSendLocks.computeIfAbsent(
                session.getId(), ignored -> new ReentrantLock()
        );
        if (!tryAcquireSendLock(session.getId(), targetSession, sendLock)) {
            return;
        }
        long startNs = System.nanoTime();
        try {
            WebSocketSession current = sessions.get(session.getId());
            if (current == null) {
                if (targetSession != session) {
                    return;
                }
                current = session;
            }
            if (!current.isOpen()) {
                removeSessionById(session.getId());
                return;
            }
            AtomicBoolean finished = new AtomicBoolean();
            AtomicBoolean timedOut = new AtomicBoolean();
            ScheduledFuture<?> timeout = scheduleSendTimeout(
                    session.getId(), current, finished, timedOut
            );
            try {
                current.sendMessage(new TextMessage(payload));
                boolean completed = finishBeforeTimeout(finished, timeout);
                long costMs = elapsedMs(startNs);
                if (completed && !timedOut.get() && costMs >= 50) {
                    log.debug("[WS-PUSH-PERF] step=send sessionId={} cost={}ms payloadBytes={}",
                            session.getId(), costMs, payload != null ? payload.length() : 0);
                }
            } catch (Exception e) {
                removeSessionById(session.getId());
                closeQuietly(current);
                log.debug("[WS-PUSH-PERF] step=sendFailed sessionId={} cost={}ms payloadBytes={}",
                        session.getId(), elapsedMs(startNs), payload != null ? payload.length() : 0);
                log.warn("[ws] event=send_failed, sessionId={}, errorType={}, errorMsg={}, action=removed",
                        session.getId(), e.getClass().getSimpleName(), e.getMessage());
            } finally {
                finishBeforeTimeout(finished, timeout);
            }
        } finally {
            sendLock.unlock();
        }
    }

    /**
     * 向指定店铺的所有浏览器客户端广播消息。
     * 仅推送给已关联该 storeId 的 session，实现多店铺数据隔离。
     */
    public void broadcastToStore(Long storeId, String payload) {
        if (storeId == null) {
            return;
        }
        long startNs = System.nanoTime();
        int[] matched = {0};
        sessions.forEach((sessionId, session) -> {
            Long sessionStoreId = sessionStoreMap.get(sessionId);
            if (storeId.equals(sessionStoreId)) {
                matched[0]++;
                send(session, payload);
            }
        });
        log.debug("[WS-PUSH-PERF] step=broadcastToStore storeId={} sessions={} matched={} cost={}ms payloadBytes={}",
                storeId, sessions.size(), matched[0], elapsedMs(startNs), payload != null ? payload.length() : 0);
    }

    /**
     * 全局广播，仅用于非敏感的系统级消息（如 lightEffectState）。
     */
    public void broadcastAll(String payload) {
        sessions.values().forEach(session -> send(session, payload));
    }

    private WebSocketSession removeSessionById(String sessionId) {
        WebSocketSession removed = sessions.remove(sessionId);
        sessionStoreMap.remove(sessionId);
        sessionUserMap.remove(sessionId);
        fabricImageBinarySessions.remove(sessionId);
        sessionSendLocks.remove(sessionId);
        return removed;
    }

    private void closeQuietly(WebSocketSession session) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close();
        } catch (IOException e) {
            log.debug("[ws] event=close_failed, sessionId={}, errorType={}, errorMsg={}",
                    session.getId(), e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    private ReentrantLock acquireSendLock(String sessionId, WebSocketSession session) {
        ReentrantLock sendLock = sessionSendLocks.get(sessionId);
        if (sendLock == null || !tryAcquireSendLock(sessionId, session, sendLock)) {
            return null;
        }
        return sendLock;
    }

    private boolean tryAcquireSendLock(String sessionId,
                                       WebSocketSession session,
                                       ReentrantLock sendLock) {
        try {
            if (sendLock.tryLock(sendTimeLimitMs, TimeUnit.MILLISECONDS)) {
                return true;
            }
            terminateTimedOutSession(sessionId, session, "send_lock_timeout");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private ScheduledFuture<?> scheduleSendTimeout(String sessionId,
                                                   WebSocketSession session,
                                                   AtomicBoolean finished,
                                                   AtomicBoolean timedOut) {
        return SEND_TIMEOUT_SCHEDULER.schedule(() -> {
            if (finished.compareAndSet(false, true)) {
                timedOut.set(true);
                terminateTimedOutSession(sessionId, session, "delegate_send_timeout");
            }
        }, sendTimeLimitMs, TimeUnit.MILLISECONDS);
    }

    private boolean finishBeforeTimeout(AtomicBoolean finished, ScheduledFuture<?> timeout) {
        boolean completed = finished.compareAndSet(false, true);
        timeout.cancel(false);
        return completed;
    }

    private void terminateTimedOutSession(String sessionId,
                                          WebSocketSession session,
                                          String reason) {
        WebSocketSession removed = removeSessionById(sessionId);
        if (removed != null) {
            closeQuietly(removed);
            log.warn("[ws] event=send_timeout, sessionId={}, reason={}, action=removed",
                    sessionId, reason);
        }
    }

    private static ScheduledThreadPoolExecutor createSendTimeoutScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "ws-send-timeout");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
