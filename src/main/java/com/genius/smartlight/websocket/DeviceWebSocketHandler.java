package com.genius.smartlight.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.convert.device.DeviceConvert;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.service.device.DeviceLastSeenService;
import com.genius.smartlight.service.device.DeviceOnlinePushService;
import com.genius.smartlight.service.device.OtaProgressStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceWebSocketHandler extends TextWebSocketHandler {

    private final DeviceSessionManager deviceSessionManager;
    private final DeviceOnlinePushService deviceOnlinePushService;
    private final ObjectMapper objectMapper;
    private final DeviceMapper deviceMapper;
    private final WebSocketPushService webSocketPushService;
    private final OtaProgressStore otaProgressStore;
    private final DeviceLastSeenService deviceLastSeenService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[ws] event=connected, wsType=device, sessionId={}, clientIp={}",
                session.getId(), getRemoteAddr(session));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.path("type").asText();
            String chipId = node.path("chipId").asText();

            if ("register".equals(type)) {
                chipId = deviceSessionManager.normalizeChipId(chipId);
                if (chipId == null) {
                    log.warn("Device register missing chipId, sessionId={}", session.getId());
                    return;
                }
                boolean wasOnline = deviceSessionManager.isOnline(chipId);
                log.info("[ws] event=device_registered, wsType=device, chipId={}, sessionId={}, clientIp={}",
                        chipId, session.getId(), getRemoteAddr(session));
                deviceSessionManager.registerDevice(chipId, session);
                Long lastSeen = deviceSessionManager.getLastSeen(chipId);
                if (wasOnline) {
                    deviceLastSeenService.persistIfDue(chipId, lastSeen);
                } else {
                    deviceLastSeenService.persistNow(chipId, lastSeen);
                }
                syncFirmwareInfo(chipId, node);
                pushSavedStateToDevice(chipId);
                deviceOnlinePushService.pushIfChanged(chipId);
                session.sendMessage(new TextMessage("{\"type\":\"registerAck\",\"data\":\"ok\"}"));
                return;
            }

            if ("ping".equals(type)) {
                chipId = deviceSessionManager.normalizeChipId(chipId);
                if (chipId != null) {
                    deviceSessionManager.touch(chipId);
                    deviceLastSeenService.persistIfDue(chipId, deviceSessionManager.getLastSeen(chipId));
                    deviceOnlinePushService.pushIfChanged(chipId);
                }
                session.sendMessage(new TextMessage("{\"type\":\"pong\",\"data\":\"ok\"}"));
                return;
            }

            log.debug("Unknown device ws message: {}", preview(message.getPayload()));
        } catch (Exception e) {
            log.warn("Invalid device websocket message: sessionId={}, errorType={}",
                    session.getId(), e.getClass().getSimpleName());
            log.debug("Invalid device websocket payload preview: {}", preview(message.getPayload()), e);
        }
    }

    private void syncFirmwareInfo(String chipId, JsonNode node) {
        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
        );
        if (device == null) {
            return;
        }

        boolean changed = false;
        boolean progressChanged = false;
        String oldOtaStatus = normalizeOtaStatus(device.getOtaStatus());
        String newOtaStatus = oldOtaStatus;

        String fwVersion = node.path("fwVersion").asText(null);
        if (fwVersion != null && !fwVersion.isBlank()) {
            device.setFirmwareVersion(fwVersion);
            changed = true;
        }

        Integer fwVersionCode = readOptionalInt(node, "fwVersionCode");
        if (fwVersionCode == null) {
            fwVersionCode = readOptionalInt(node, "firmwareVersionCode");
        }
        if (fwVersionCode != null) {
            device.setFirmwareVersionCode(fwVersionCode);
            changed = true;
        }

        String channel = node.path("firmwareChannel").asText(null);
        if (channel == null || channel.isBlank()) {
            channel = node.path("channel").asText(null);
        }
        if (channel != null && !channel.isBlank()) {
            device.setFirmwareChannel(channel);
            changed = true;
        }

        String reportedOtaStatus = node.path("otaStatus").asText(null);
        boolean otaStatusReported = reportedOtaStatus != null && !reportedOtaStatus.isBlank();
        if (otaStatusReported) {
            newOtaStatus = normalizeOtaStatus(reportedOtaStatus);
            device.setOtaStatus(newOtaStatus);
            changed = true;
        } else if (device.getOtaStatus() == null || device.getOtaStatus().isBlank()) {
            newOtaStatus = "idle";
            device.setOtaStatus("idle");
            changed = true;
        }

        Integer otaProgress = readOptionalInt(node, "otaProgress");
        progressChanged = updateOtaProgress(chipId, oldOtaStatus, newOtaStatus, otaProgress, otaStatusReported);

        if (device.getSelfTestJson() != null || device.getSelfTestTime() != null) {
            device.setSelfTestJson(null);
            device.setSelfTestTime(null);
            changed = true;
        }

        if (changed) {
            deviceMapper.updateById(device);
        }
        if (changed || progressChanged) {
            webSocketPushService.pushState(DeviceConvert.convert(device));
        }
    }

    private void pushSavedStateToDevice(String chipId) {
        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
        );
        if (device == null) {
            return;
        }

        webSocketPushService.pushStateToDevice(chipId, DeviceConvert.convert(device));
    }

    private boolean updateOtaProgress(String chipId, String oldStatus, String newStatus, Integer progress, boolean statusReported) {
        Integer before = otaProgressStore.getProgress(chipId);

        if (progress != null) {
            otaProgressStore.setProgress(chipId, progress);
        }

        if (!statusReported) {
            return progress != null && !sameProgress(before, otaProgressStore.getProgress(chipId));
        }

        if ("success".equals(newStatus)) {
            otaProgressStore.clearProgress(chipId);
        } else if ("idle".equals(newStatus)) {
            otaProgressStore.clearProgress(chipId);
        } else if ("failed".equals(newStatus)) {
            if (otaProgressStore.getProgress(chipId) == null) {
                otaProgressStore.setProgress(chipId, 0);
            }
        } else if ("updating".equals(newStatus)) {
            if (progress != null) {
                otaProgressStore.setProgress(chipId, progress);
            } else if (otaProgressStore.getProgress(chipId) == null) {
                otaProgressStore.setProgress(chipId, 0);
            }
        }

        return !sameProgress(before, otaProgressStore.getProgress(chipId))
                || (!"updating".equals(oldStatus) && "updating".equals(newStatus));
    }

    private boolean sameProgress(Integer a, Integer b) {
        return a == null ? b == null : a.equals(b);
    }

    private String normalizeOtaStatus(String otaStatus) {
        String value = otaStatus == null ? "" : otaStatus.trim().toLowerCase(Locale.ROOT);
        if ("updating".equals(value) || "success".equals(value) || "failed".equals(value)) {
            return value;
        }
        return "idle";
    }

    private Integer readOptionalInt(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        if (value.isTextual() && !value.asText().isBlank()) {
            try {
                return Integer.parseInt(value.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String preview(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String chipId = deviceSessionManager.removeBySession(session);
        boolean abnormal = status != null && !CloseStatus.NORMAL.equals(status);
        if (abnormal) {
            log.warn("[ws] event=disconnected, wsType=device, sessionId={}, chipId={}, closeStatus={}, closeReason={}",
                    session.getId(), chipId != null ? chipId : "-",
                    status != null ? status.getCode() : "-",
                    status != null && status.getReason() != null ? status.getReason() : "");
        } else {
            log.info("[ws] event=disconnected, wsType=device, sessionId={}, chipId={}",
                    session.getId(), chipId != null ? chipId : "-");
        }
        if (chipId != null) {
            deviceOnlinePushService.pushIfChanged(chipId);
        }
    }

    private String getRemoteAddr(WebSocketSession session) {
        if (session.getRemoteAddress() != null) {
            String addr = session.getRemoteAddress().toString();
            return addr.startsWith("/") ? addr.substring(1) : addr;
        }
        return "unknown";
    }
}
