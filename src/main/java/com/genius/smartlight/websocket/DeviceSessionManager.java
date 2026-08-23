package com.genius.smartlight.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class DeviceSessionManager {

    private static final long ONLINE_TIMEOUT_MS = 15_000L;
    private static final long COLLISION_GUARD_ACK_TIMEOUT_MS = 3_000L;
    private static final ObjectMapper COLLISION_GUARD_JSON = new ObjectMapper();

    private final Map<String, WebSocketSession> deviceSessionMap = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionDeviceMap = new ConcurrentHashMap<>();
    private final Map<String, String> uploadTokenMap = new ConcurrentHashMap<>();
    private final Map<String, CollisionGuardAckWaiter> collisionGuardAckWaiters = new ConcurrentHashMap<>();

    public void registerDevice(String chipId, WebSocketSession session) {
        String normalizedChipId = normalizeChipId(chipId);
        if (normalizedChipId == null) {
            log.warn("registerDevice ignored blank chipId, sessionId={}", session.getId());
            return;
        }
        logIfNormalized(chipId, normalizedChipId);

        String oldChipIdForSession = sessionDeviceMap.put(session.getId(), normalizedChipId);
        if (oldChipIdForSession != null && !oldChipIdForSession.equals(normalizedChipId)) {
            deviceSessionMap.remove(oldChipIdForSession, session);
        }

        WebSocketSession oldSession = deviceSessionMap.put(normalizedChipId, session);
        lastSeenMap.put(normalizedChipId, System.currentTimeMillis());

        if (oldSession != null && oldSession != session && oldSession.isOpen()) {
            try {
                oldSession.close();
            } catch (IOException e) {
                log.warn("[ws] event=close_old_session_failed, wsType=device, chipId={}, errorMsg={}", normalizedChipId, e.getMessage());
            }
        }

        // device_registered logged by DeviceWebSocketHandler
    }

    public String refreshUploadToken(String chipId) {
        String normalizedChipId = normalizeChipId(chipId);
        if (normalizedChipId == null) {
            return null;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        uploadTokenMap.put(normalizedChipId, token);
        return token;
    }

    public boolean validateUploadToken(String chipId, String token) {
        String trackedChipId = resolveTrackedChipId(chipId);
        if (trackedChipId == null || token == null || token.isBlank()) {
            return false;
        }
        String expected = uploadTokenMap.get(trackedChipId);
        return expected != null && expected.equals(token.trim());
    }

    public void touch(String chipId) {
        String normalizedChipId = normalizeChipId(chipId);
        if (normalizedChipId != null) {
            logIfNormalized(chipId, normalizedChipId);
            lastSeenMap.put(normalizedChipId, System.currentTimeMillis());
        }
    }

    public boolean isOnline(String chipId) {
        String trackedChipId = resolveTrackedChipId(chipId);
        if (trackedChipId == null) {
            return false;
        }

        WebSocketSession session = deviceSessionMap.get(trackedChipId);
        Long lastSeen = lastSeenMap.get(trackedChipId);

        return session != null
                && session.isOpen()
                && lastSeen != null
                && System.currentTimeMillis() - lastSeen <= ONLINE_TIMEOUT_MS;
    }

    public Long getLastSeen(String chipId) {
        String trackedChipId = resolveTrackedChipId(chipId);
        return trackedChipId == null ? null : lastSeenMap.get(trackedChipId);
    }

    public Set<String> getTrackedChipIds() {
        Set<String> chipIds = new HashSet<>(lastSeenMap.keySet());
        chipIds.addAll(deviceSessionMap.keySet());
        return chipIds;
    }

    public Set<String> getOnlineChipIds() {
        Set<String> result = new HashSet<>();
        for (String chipId : deviceSessionMap.keySet()) {
            if (isOnline(chipId)) {
                result.add(chipId);
            }
        }
        return result;
    }

    public boolean sendToDevice(String chipId, String payload) {
        String trackedChipId = resolveTrackedChipId(chipId);
        if (trackedChipId == null) {
            log.warn("sendToDevice failed: blank chipId={}", chipId);
            return false;
        }

        WebSocketSession session = deviceSessionMap.get(trackedChipId);
        if (session == null) {
            log.warn("sendToDevice failed: no session for chipId={}", trackedChipId);
            return false;
        }
        if (!session.isOpen()) {
            log.warn("sendToDevice failed: session closed for chipId={}", trackedChipId);
            return false;
        }

        CollisionGuardParkCommand guardCommand = parseCollisionGuardParkCommand(payload);
        CollisionGuardAckWaiter guardWaiter = null;
        String guardWaiterKey = null;
        if (guardCommand != null) {
            guardWaiterKey = collisionGuardWaiterKey(trackedChipId, guardCommand.guardId());
            guardWaiter = new CollisionGuardAckWaiter(trackedChipId, guardCommand.guardId());
            CollisionGuardAckWaiter previous = collisionGuardAckWaiters.putIfAbsent(guardWaiterKey, guardWaiter);
            if (previous != null) {
                log.warn("collision guard park rejected: duplicate waiter, chipId={}, guardId={}",
                        trackedChipId, guardCommand.guardId());
                return false;
            }
        }

        try {
            session.sendMessage(new TextMessage(payload));
            log.debug("sendToDevice success: chipId={}", trackedChipId);
            if (guardWaiter == null) {
                return true;
            }

            log.info("collision guard park sent; awaiting lamp acknowledgement, chipId={}, guardId={}, timeoutMs={}",
                    trackedChipId, guardWaiter.guardId(), COLLISION_GUARD_ACK_TIMEOUT_MS);
            boolean completed = guardWaiter.await(COLLISION_GUARD_ACK_TIMEOUT_MS);
            if (!completed) {
                log.warn("collision guard acknowledgement timeout; slider movement remains blocked, chipId={}, guardId={}",
                        trackedChipId, guardWaiter.guardId());
                sendCollisionGuardReleaseBestEffort(session, trackedChipId, guardWaiter.guardId());
                return false;
            }
            if (!"accepted".equals(guardWaiter.status())) {
                log.warn("collision guard acknowledgement rejected; slider movement remains blocked, chipId={}, guardId={}, status={}",
                        trackedChipId, guardWaiter.guardId(), guardWaiter.status());
                sendCollisionGuardReleaseBestEffort(session, trackedChipId, guardWaiter.guardId());
                return false;
            }

            log.info("collision guard acknowledged; park timer may start, chipId={}, guardId={}",
                    trackedChipId, guardWaiter.guardId());
            return true;
        } catch (IOException e) {
            log.error("[ws] event=send_failed, wsType=device, chipId={}, errorType={}, errorMsg={}",
                    trackedChipId, e.getClass().getSimpleName(), e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (guardWaiter != null) {
                log.warn("collision guard acknowledgement wait interrupted; slider movement remains blocked, chipId={}, guardId={}",
                        trackedChipId, guardWaiter.guardId());
                sendCollisionGuardReleaseBestEffort(session, trackedChipId, guardWaiter.guardId());
            }
            return false;
        } finally {
            if (guardWaiterKey != null && guardWaiter != null) {
                collisionGuardAckWaiters.remove(guardWaiterKey, guardWaiter);
            }
        }
    }

    public void confirmCollisionGuard(String chipId, String guardId, String status) {
        String trackedChipId = resolveTrackedChipId(chipId);
        if (trackedChipId == null || guardId == null || guardId.isBlank()) {
            return;
        }
        String normalizedGuardId = guardId.trim();
        String normalizedStatus = status == null ? "unknown" : status.trim().toLowerCase();
        CollisionGuardAckWaiter waiter = collisionGuardAckWaiters.get(
                collisionGuardWaiterKey(trackedChipId, normalizedGuardId)
        );
        if (waiter == null) {
            log.debug("collision guard acknowledgement ignored: no waiter, chipId={}, guardId={}, status={}",
                    trackedChipId, normalizedGuardId, normalizedStatus);
            return;
        }
        if (!Set.of("accepted", "rejected", "error").contains(normalizedStatus)) {
            log.warn("collision guard acknowledgement ignored: unsupported status, chipId={}, guardId={}, status={}",
                    trackedChipId, normalizedGuardId, normalizedStatus);
            return;
        }
        waiter.complete(normalizedStatus);
    }

    public String removeBySession(WebSocketSession session) {
        String targetChipId = sessionDeviceMap.remove(session.getId());
        if (targetChipId == null) {
            for (Map.Entry<String, WebSocketSession> entry : deviceSessionMap.entrySet()) {
                if (entry.getValue() == session) {
                    targetChipId = entry.getKey();
                    break;
                }
            }
        }

        if (targetChipId != null) {
            deviceSessionMap.remove(targetChipId, session);
            failCollisionGuardWaitersForDevice(targetChipId, "disconnected");
            // disconnected logged by DeviceWebSocketHandler
        } else {
            log.info("[ws] event=disconnected, wsType=device, sessionId={}, chipId=unregistered", session.getId());
        }
        return targetChipId;
    }

    public String normalizeChipId(String chipId) {
        if (chipId == null) {
            return null;
        }
        String value = chipId.trim();
        return value.isEmpty() ? null : value;
    }

    private CollisionGuardParkCommand parseCollisionGuardParkCommand(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = COLLISION_GUARD_JSON.readTree(payload);
            if (!"lampCollisionGuard".equals(node.path("type").asText())) {
                return null;
            }
            if (!"park".equalsIgnoreCase(node.path("action").asText("park"))) {
                return null;
            }
            String guardId = node.path("guardId").asText("").trim();
            if (guardId.isEmpty()) {
                log.warn("collision guard park command missing guardId");
                return null;
            }
            return new CollisionGuardParkCommand(guardId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void sendCollisionGuardReleaseBestEffort(
            WebSocketSession session,
            String chipId,
            String guardId) {
        try {
            ObjectNode release = COLLISION_GUARD_JSON.createObjectNode();
            release.put("type", "lampCollisionGuard");
            release.put("action", "release");
            release.put("guardId", guardId);
            session.sendMessage(new TextMessage(COLLISION_GUARD_JSON.writeValueAsString(release)));
            log.info("collision guard release sent after failed acknowledgement, chipId={}, guardId={}",
                    chipId, guardId);
        } catch (Exception e) {
            log.warn("collision guard release after failed acknowledgement could not be sent, chipId={}, guardId={}, errorType={}",
                    chipId, guardId, e.getClass().getSimpleName());
        }
    }

    private void failCollisionGuardWaitersForDevice(String chipId, String status) {
        for (CollisionGuardAckWaiter waiter : collisionGuardAckWaiters.values()) {
            if (waiter.chipId().equals(chipId)) {
                waiter.complete(status);
            }
        }
    }

    private String collisionGuardWaiterKey(String chipId, String guardId) {
        return chipId + "#" + guardId;
    }

    private String resolveTrackedChipId(String chipId) {
        String normalizedChipId = normalizeChipId(chipId);
        if (normalizedChipId == null) {
            return null;
        }
        logIfNormalized(chipId, normalizedChipId);
        if (deviceSessionMap.containsKey(normalizedChipId) || lastSeenMap.containsKey(normalizedChipId)) {
            return normalizedChipId;
        }
        String trackedChipId = findCaseInsensitiveKey(deviceSessionMap, normalizedChipId);
        if (trackedChipId == null) {
            trackedChipId = findCaseInsensitiveKey(lastSeenMap, normalizedChipId);
        }
        if (trackedChipId != null) {
            log.warn("chipId case mismatch, requested={}, tracked={}", normalizedChipId, trackedChipId);
            return trackedChipId;
        }
        return normalizedChipId;
    }

    private String findCaseInsensitiveKey(Map<String, ?> map, String chipId) {
        for (String trackedChipId : map.keySet()) {
            if (trackedChipId.equalsIgnoreCase(chipId)) {
                return trackedChipId;
            }
        }
        return null;
    }

    private void logIfNormalized(String rawChipId, String normalizedChipId) {
        if (rawChipId != null && !rawChipId.equals(normalizedChipId)) {
            log.warn("chipId normalized by trim, raw='{}', normalized='{}'", rawChipId, normalizedChipId);
        }
    }

    private record CollisionGuardParkCommand(String guardId) {
    }

    private static final class CollisionGuardAckWaiter {
        private final String chipId;
        private final String guardId;
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile String status = "pending";

        private CollisionGuardAckWaiter(String chipId, String guardId) {
            this.chipId = chipId;
            this.guardId = guardId;
        }

        private String chipId() {
            return chipId;
        }

        private String guardId() {
            return guardId;
        }

        private String status() {
            return status;
        }

        private void complete(String nextStatus) {
            if (latch.getCount() == 0L) {
                return;
            }
            status = nextStatus;
            latch.countDown();
        }

        private boolean await(long timeoutMs) throws InterruptedException {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }
}
