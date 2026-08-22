package com.genius.smartlight.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.DeviceTypeUtil;
import com.genius.smartlight.convert.device.DeviceConvert;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.service.device.DeviceCamService;
import com.genius.smartlight.service.device.DeviceLastSeenService;
import com.genius.smartlight.service.device.DeviceOnlinePushService;
import com.genius.smartlight.service.device.OtaProgressStore;
import com.genius.smartlight.service.device.SliderMotionStateService;
import com.genius.smartlight.vo.device.DeviceLampClothStateReqVO;
import com.genius.smartlight.vo.device.DeviceLampProximityStateReqVO;
import com.genius.smartlight.vo.device.DeviceCamPresenceReqVO;
import com.genius.smartlight.vo.device.DeviceCamRoiConfigVO;
import com.genius.smartlight.vo.device.DeviceCamStatusReqVO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.vo.device.DeviceSliderStatusReqVO;
import com.genius.smartlight.vo.device.DeviceTrackingStatusReqVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.time.LocalDateTime;

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
    private final DeviceCamService deviceCamService;
    private final SliderMotionStateService sliderMotionStateService;

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
            String chipId = readChipId(node);

            if ("register".equals(type)) {
                if (chipId == null) {
                    log.warn("Device register missing chipId, sessionId={}", session.getId());
                    return;
                }
                DeviceDO knownDevice = findDevice(chipId);
                if (knownDevice == null) {
                    log.warn("Device register rejected: unknown chipId={}, sessionId={}, clientIp={}",
                            chipId, session.getId(), getRemoteAddr(session));
                    session.close(CloseStatus.POLICY_VIOLATION.withReason("unknown chipId"));
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
                if (DeviceTypeUtil.isLampLike(knownDevice.getDeviceType())) {
                    sliderMotionStateService.clearSpeedConfirmation(chipId);
                    try {
                        String savedSpeed = sliderMotionStateService
                                .getSnapshot(chipId, knownDevice.getStoreId()).speedMode();
                        ObjectNode speedCommand = objectMapper.createObjectNode();
                        speedCommand.put("type", "arm_speed");
                        speedCommand.put("speed", savedSpeed);
                        deviceSessionManager.sendToDevice(chipId, speedCommand.toString());
                    } catch (RuntimeException exception) {
                        log.warn("failed to restore slider speed on register, chipId={}", chipId, exception);
                    }
                }
                deviceOnlinePushService.pushIfChanged(chipId);
                String uploadToken = deviceSessionManager.refreshUploadToken(chipId);
                ObjectNode ack = objectMapper.createObjectNode();
                ack.put("type", "registerAck");
                ack.put("data", "ok");
                ack.put("deviceUploadToken", uploadToken);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ack)));
                pushCamRoiConfigIfNeeded(session, knownDevice);
                if (DeviceTypeUtil.isCaptureController(knownDevice.getDeviceType())) {
                    deviceCamService.pushCaptureControllerConfigForDevice(knownDevice.getChipId());
                }
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

            if ("lampClothState".equals(type)) {
                chipId = requireChipId(chipId, session, "lampClothState");
                if (chipId == null) return;
                DeviceLampClothStateReqVO reqVO = new DeviceLampClothStateReqVO();
                reqVO.setChipId(chipId);
                reqVO.setClothState(node.path("clothState").asText("unknown"));
                if (node.hasNonNull("lastTakenAt")) {
                    reqVO.setLastTakenAt(node.path("lastTakenAt").asText());
                }
                if (node.hasNonNull("tracking")) {
                    reqVO.setTracking(node.path("tracking").asBoolean());
                }
                deviceCamService.reportLampClothState(reqVO);
                return;
            }

            if ("lampProximityState".equals(type)) {
                chipId = requireChipId(chipId, session, "lampProximityState");
                if (chipId == null) return;
                DeviceLampProximityStateReqVO reqVO = new DeviceLampProximityStateReqVO();
                reqVO.setChipId(chipId);
                reqVO.setNearby(node.path("nearby").asBoolean(false));
                deviceCamService.reportLampProximityState(reqVO);
                return;
            }

            if ("camStatus".equals(type)) {
                chipId = requireChipId(chipId, session, "camStatus");
                if (chipId == null) return;
                DeviceCamStatusReqVO reqVO = new DeviceCamStatusReqVO();
                reqVO.setCamChipId(chipId);
                reqVO.setWorkStatus(node.path("workStatus").asText(node.path("status").asText("monitoring")));
                if (node.hasNonNull("activeTargetIndex")) {
                    reqVO.setActiveTargetIndex(node.path("activeTargetIndex").asInt());
                } else if (node.hasNonNull("targetIndex")) {
                    reqVO.setActiveTargetIndex(node.path("targetIndex").asInt());
                }
                reqVO.setActiveTargetChipId(node.path("activeTargetChipId").asText(node.path("targetChipId").asText(null)));
                reqVO.setMessage(node.path("message").asText(null));
                deviceCamService.reportStatus(reqVO);
                return;
            }

            if ("camPresence".equals(type)) {
                chipId = requireChipId(chipId, session, "camPresence");
                if (chipId == null) return;
                DeviceCamPresenceReqVO reqVO = new DeviceCamPresenceReqVO();
                reqVO.setCamChipId(chipId);
                reqVO.setWorkStatus(node.path("workStatus").asText("monitoring"));
                if (node.hasNonNull("personCount")) {
                    reqVO.setPersonCount(node.path("personCount").asInt());
                }
                if (node.hasNonNull("confidence")) {
                    reqVO.setConfidence(node.path("confidence").asDouble());
                }
                reqVO.setDetectTime(node.path("detectTime").asText(node.path("timestamp").asText(null)));
                JsonNode areasNode = node.get("areas");
                if (areasNode != null && areasNode.isArray()) {
                    List<DeviceCamPresenceReqVO.PresenceArea> areas = new ArrayList<>();
                    for (JsonNode areaNode : areasNode) {
                        areas.add(objectMapper.treeToValue(areaNode, DeviceCamPresenceReqVO.PresenceArea.class));
                    }
                    reqVO.setAreas(areas);
                }
                deviceCamService.reportPresence(reqVO);
                return;
            }

            if ("personDetection".equals(type)) {
                chipId = readChipId(node);
                if (chipId == null) {
                    log.warn("personDetection missing chipId, sessionId={}", session.getId());
                    return;
                }
                DeviceDO device = findDevice(chipId);
                if (device == null) {
                    log.warn("personDetection rejected: unknown chipId={}, sessionId={}", chipId, session.getId());
                    return;
                }
                deviceSessionManager.touch(chipId);
                deviceLastSeenService.persistIfDue(chipId, deviceSessionManager.getLastSeen(chipId));
                ObjectNode payload = node.deepCopy();
                payload.remove("type");
                payload.put("chipId", chipId);
                payload.put("source", "deviceWs");
                webSocketPushService.pushDevicePersonDetection(payload, device.getStoreId());
                return;
            }

            if ("selfTest".equals(type)) {
                chipId = requireChipId(chipId, session, "selfTest");
                if (chipId == null) return;
                JsonNode selfTestNode = node.get("selfTest");
                if (selfTestNode == null || !selfTestNode.path("done").asBoolean(false)) {
                    log.warn("selfTest ignored incomplete payload, chipId={}", chipId);
                    return;
                }
                deviceSessionManager.touch(chipId);
                saveDeviceSelfTest(chipId, selfTestNode);
                return;
            }

            if ("sliderStatus".equals(type)) {
                chipId = requireChipId(chipId, session, "sliderStatus");
                if (chipId == null) return;
                DeviceSliderStatusReqVO reqVO = new DeviceSliderStatusReqVO();
                reqVO.setChipId(chipId);
                reqVO.setTaskId(node.path("taskId").asText(null));
                reqVO.setStatus(node.path("status").asText("unknown"));
                if (node.hasNonNull("targetMm")) {
                    reqVO.setTargetMm(node.path("targetMm").asDouble());
                }
                if (node.hasNonNull("positionSteps")) {
                    reqVO.setPositionSteps(node.path("positionSteps").asLong());
                }
                if (node.hasNonNull("uptimeMs")) {
                    reqVO.setUptimeMs(node.path("uptimeMs").asLong());
                }
                deviceCamService.reportSliderStatus(reqVO);
                return;
            }

            if ("armSpeedStatus".equals(type)) {
                chipId = requireChipId(chipId, session, "armSpeedStatus");
                if (chipId == null) return;
                DeviceDO device = findDevice(chipId);
                if (device != null && DeviceTypeUtil.isLampLike(device.getDeviceType())) {
                    sliderMotionStateService.confirmSpeedMode(
                            chipId, device.getStoreId(), node.path("speed").asText(null));
                }
                return;
            }

            if ("collisionGuardStatus".equals(type)) {
                chipId = requireChipId(chipId, session, "collisionGuardStatus");
                if (chipId == null) return;
                log.debug("collision guard forwarded, chipId={}, guardId={}, status={}, nanoFeedback={}",
                        chipId, node.path("guardId").asText(""),
                        node.path("status").asText("unknown"),
                        node.path("nanoFeedback").asBoolean(false));
                return;
            }

            if ("trackingStatus".equals(type)) {
                chipId = requireChipId(chipId, session, "trackingStatus");
                if (chipId == null) return;
                DeviceTrackingStatusReqVO reqVO = new DeviceTrackingStatusReqVO();
                reqVO.setChipId(chipId);
                reqVO.setRole(node.path("role").asText(null));
                reqVO.setTrackingStatus(node.path("trackingStatus").asText(node.path("status").asText("unknown")));
                reqVO.setCamChipId(node.path("camChipId").asText(null));
                reqVO.setLampChipId(node.path("lampChipId").asText(node.path("targetChipId").asText(null)));
                if (node.hasNonNull("targetIndex")) {
                    reqVO.setTargetIndex(node.path("targetIndex").asInt());
                }
                if (node.hasNonNull("confidence")) {
                    reqVO.setConfidence(node.path("confidence").asDouble());
                }
                if (node.hasNonNull("sequence")) {
                    reqVO.setSequence(node.path("sequence").asLong());
                } else if (node.hasNonNull("seq")) {
                    reqVO.setSequence(node.path("seq").asLong());
                }
                reqVO.setMessage(node.path("message").asText(null));
                deviceCamService.reportTrackingStatus(reqVO);
                return;
            }

            log.debug("Unknown device ws message: {}", preview(message.getPayload()));
        } catch (Exception e) {
            log.warn("Invalid device websocket message: sessionId={}, errorType={}",
                    session.getId(), e.getClass().getSimpleName());
            log.debug("Invalid device websocket payload preview: {}", preview(message.getPayload()), e);
        }
    }

    private DeviceDO findDevice(String chipId) {
        return deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
                        .last("limit 1")
        );
    }

    /**
     * 规范化 chipId + 空值校验 + 心跳更新，用于非 register/ping 消息类型
     * 的公共样板逻辑。返回 null 表示 chipId 无效，调用方应直接 return。
     */
    private String requireChipId(String chipId, WebSocketSession session, String msgType) {
        String normalized = deviceSessionManager.normalizeChipId(chipId);
        if (normalized == null) {
            log.warn("{} missing chipId, sessionId={}", msgType, session.getId());
            return null;
        }
        deviceSessionManager.touch(normalized);
        return normalized;
    }

    private String readChipId(JsonNode node) {
        String chipId = node.path("chipId").asText(null);
        if (chipId == null || chipId.isBlank()) {
            chipId = node.path("id").asText(null);
        }
        return deviceSessionManager.normalizeChipId(chipId);
    }

    private void pushCamRoiConfigIfNeeded(WebSocketSession session, DeviceDO device) {
        if (!DeviceTypeUtil.isCam(device.getDeviceType())) {
            return;
        }
        try {
            DeviceCamRoiConfigVO config = deviceCamService.getRoiConfigForDevice(device.getChipId());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(WsMessage.of("cameraRoiConfig", config))));
        } catch (Exception e) {
            log.warn("push cam roi config failed, chipId={}", device.getChipId(), e);
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

        String ip = node.path("ip").asText(null);
        if (ip != null && !ip.isBlank()) {
            device.setIp(ip);
            changed = true;
        }

        String deviceType = node.path("deviceType").asText(null);
        if (deviceType != null && !deviceType.isBlank()) {
            device.setDeviceType(deviceType.trim().toLowerCase(Locale.ROOT));
            changed = true;
        }

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

        DeviceRespVO stateVO = DeviceConvert.convert(device);
        if (DeviceTypeUtil.isLampLike(stateVO.getDeviceType())) {
            webSocketPushService.pushStateToDevice(chipId, stateVO);
        }
    }

    private void saveDeviceSelfTest(String chipId, JsonNode selfTestNode) throws Exception {
        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, chipId)
                        .last("limit 1")
        );
        if (device == null) {
            log.warn("selfTest rejected: unknown chipId={}", chipId);
            return;
        }

        device.setSelfTestJson(objectMapper.writeValueAsString(selfTestNode));
        device.setSelfTestTime(LocalDateTime.now());
        device.setUpdateTime(LocalDateTime.now());
        deviceMapper.updateById(device);
        webSocketPushService.pushState(otaProgressStore.applyProgress(DeviceConvert.convert(device)));
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
            sliderMotionStateService.clearSpeedConfirmation(chipId);
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
