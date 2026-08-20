package com.genius.smartlight.service.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genius.smartlight.common.DeviceTypeUtil;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.DurationRecordMapper;
import com.genius.smartlight.vo.device.DeviceCamTrackingControlReqVO;
import com.genius.smartlight.vo.device.DeviceTrackingStatusReqVO;
import com.genius.smartlight.vo.device.DeviceTrackingStatusRespVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ROI-free person tracking coordinator.
 * Lamp ToF identifies the physical area; high-frequency pan/tilt stays on the
 * LAN between Camera and Lamp over UDP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FixedPersonTrackingService {

    public static final int TRACKING_PROTOCOL_VERSION = 1;
    public static final int TRACKING_UDP_PORT = 4211;

    private final DeviceMapper deviceMapper;
    private final DurationRecordMapper durationRecordMapper;
    private final DeviceSessionManager deviceSessionManager;
    private final WebSocketPushService webSocketPushService;
    private final DeviceCamCaptureConfigService captureConfigService;
    private final ObjectMapper objectMapper;

    private final Map<String, Boolean> nearbyByLamp = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> nearbySinceByLamp = new ConcurrentHashMap<>();
    private final Map<String, String> clothStateByLamp = new ConcurrentHashMap<>();
    private final Map<String, TrackingSession> activeByCam = new ConcurrentHashMap<>();
    private final Map<String, String> activeCamByLamp = new ConcurrentHashMap<>();

    public void onLampProximity(String lampChipId, boolean nearby) {
        String lamp = normalizeChipId(lampChipId);
        if (lamp == null) {
            return;
        }

        boolean wasNearby = Boolean.TRUE.equals(nearbyByLamp.put(lamp, nearby));
        LocalDateTime now = LocalDateTime.now();
        if (nearby) {
            if (!wasNearby) {
                nearbySinceByLamp.put(lamp, now);
            }
            evaluateAutoTracking(lamp);
            return;
        }

        if (wasNearby) {
            persistDwell(lamp, nearbySinceByLamp.remove(lamp), now);
        } else {
            nearbySinceByLamp.remove(lamp);
        }
        stopAutoSessionForLamp(lamp, "ToF target left");
    }

    public void onLampClothState(String lampChipId, String clothState) {
        String lamp = normalizeChipId(lampChipId);
        if (lamp == null) {
            return;
        }
        String state = clothState == null ? "unknown" : clothState.trim().toLowerCase(Locale.ROOT);
        clothStateByLamp.put(lamp, state);
        if (!"taken".equals(state)) {
            stopAutoSessionForLamp(lamp, "garment returned to rack");
            return;
        }
        evaluateAutoTracking(lamp);
    }

    public DeviceTrackingStatusRespVO startManually(DeviceCamTrackingControlReqVO reqVO) {
        DeviceDO cam = requireCam(reqVO.getCamChipId());
        DeviceDO lamp = requireLamp(reqVO.getTargetChipId());
        requireSameStore(cam, lamp);
        int targetIndex = captureConfigService.getForCurrentStore(cam.getChipId()).getTargets().stream()
                .filter(target -> sameChipId(target.getLampChipId(), lamp.getChipId()))
                .map(target -> target.getIndex() == null ? 1 : target.getIndex())
                .findFirst()
                .orElseThrow(() -> new ServiceException("目标灯未绑定到该 Camera 的拍摄配置"));
        return startSession(cam, lamp, targetIndex, TrackingSource.MANUAL, "manual tracking started");
    }

    public DeviceTrackingStatusRespVO stopManually(DeviceCamTrackingControlReqVO reqVO) {
        String camChipId = normalizeChipId(reqVO.getCamChipId());
        if (camChipId == null) {
            throw new ServiceException("camChipId 不能为空");
        }
        TrackingSession session = activeByCam.get(camChipId);
        if (session == null) {
            DeviceDO cam = requireCam(camChipId);
            return status(null, cam.getChipId(), reqVO.getTargetChipId(), reqVO.getTargetIndex(),
                    "stopped", "no active tracking session", cam.getStoreId());
        }
        stopSession(session, "manual tracking stopped", true);
        return status(session.sessionId(), session.camChipId(), session.lampChipId(), session.targetIndex(),
                "stopped", "manual tracking stopped", session.storeId());
    }

    public void onTrackingStatus(DeviceTrackingStatusReqVO reqVO) {
        if (reqVO == null) {
            return;
        }
        String trackingStatus = normalizeStatus(reqVO.getTrackingStatus());
        if (trackingStatus == null) {
            return;
        }
        TrackingSession session = findSession(reqVO);
        if (session == null) {
            return;
        }
        if (reqVO.getSessionId() != null
                && !reqVO.getSessionId().isBlank()
                && !session.sessionId().equals(reqVO.getSessionId().trim())) {
            log.debug("ignore stale tracking status, sessionId={}, activeSession={}",
                    reqVO.getSessionId(), session.sessionId());
            return;
        }

        if (Set.of("error", "lost", "stopped", "timeout").contains(trackingStatus)) {
            stopSession(session, "tracking " + trackingStatus, true);
            return;
        }

        if (Set.of("armed", "tracking").contains(trackingStatus)) {
            status(session.sessionId(), session.camChipId(), session.lampChipId(), session.targetIndex(),
                    trackingStatus, reqVO.getMessage(), session.storeId());
        }
    }

    public void onDeviceOnlineStatusChanged(String chipId, boolean online) {
        if (online) {
            return;
        }
        String normalized = normalizeChipId(chipId);
        if (normalized == null) {
            return;
        }
        if (nearbySinceByLamp.containsKey(normalized)) {
            persistDwell(normalized, nearbySinceByLamp.remove(normalized), LocalDateTime.now());
            nearbyByLamp.put(normalized, false);
        }
        activeByCam.values().stream()
                .filter(session -> sameChipId(normalized, session.camChipId())
                        || sameChipId(normalized, session.lampChipId()))
                .findFirst()
                .ifPresent(session -> stopSession(session, "tracking device offline", true));
    }

    private void persistDwell(String lampChipId, LocalDateTime startedAt, LocalDateTime endedAt) {
        if (startedAt == null || endedAt == null) {
            return;
        }
        long durationMs = ChronoUnit.MILLIS.between(startedAt, endedAt);
        if (durationMs <= 0L || durationMs > 3_600_000L) {
            return;
        }
        try {
            DeviceDO lamp = requireLamp(lampChipId);
            durationRecordMapper.insertOrIncrease(
                    lamp.getId(),
                    lamp.getStoreId(),
                    lamp.getChipId(),
                    LocalDate.now(),
                    durationMs,
                    endedAt
            );
        } catch (RuntimeException exception) {
            log.warn("failed to persist ToF dwell duration, lampChipId={}, durationMs={}",
                    lampChipId, durationMs, exception);
        }
    }

    private void evaluateAutoTracking(String lampChipId) {
        if (!Boolean.TRUE.equals(nearbyByLamp.get(lampChipId))
                || !"taken".equals(clothStateByLamp.get(lampChipId))) {
            return;
        }
        DeviceDO lamp = requireLamp(lampChipId);
        Optional<DeviceCamCaptureConfigService.CameraLampBinding> binding =
                captureConfigService.findBindingForLamp(lamp.getChipId(), lamp.getStoreId());
        if (binding.isEmpty()) {
            log.debug("ToF tracking ignored: no camera binding for lamp={}", lamp.getChipId());
            return;
        }

        DeviceCamCaptureConfigService.CameraLampBinding target = binding.get();
        DeviceDO cam = requireCam(target.camChipId());
        TrackingSession active = activeByCam.get(cam.getChipId());
        if (active != null && sameChipId(active.lampChipId(), lamp.getChipId())) {
            return;
        }
        if (active != null) {
            stopSession(active, "ToF tracking target changed", true);
        }
        try {
            startSession(cam, lamp, target.targetIndex(), TrackingSource.AUTO_TOF,
                    "ToF target entered tracking area");
        } catch (RuntimeException exception) {
            log.warn("failed to start ToF tracking, camChipId={}, lampChipId={}",
                    cam.getChipId(), lamp.getChipId(), exception);
            status(null, cam.getChipId(), lamp.getChipId(), target.targetIndex(),
                    "error", exception.getMessage(), cam.getStoreId());
        }
    }

    private DeviceTrackingStatusRespVO startSession(
            DeviceDO cam,
            DeviceDO lamp,
            int targetIndex,
            TrackingSource source,
            String successMessage) {
        if (!deviceSessionManager.isOnline(cam.getChipId())) {
            throw new ServiceException("摄像头离线，无法开始追踪");
        }
        if (!deviceSessionManager.isOnline(lamp.getChipId())) {
            throw new ServiceException("目标灯离线，无法开始追踪");
        }
        String camIp = normalizeIpv4(cam.getIp());
        String lampIp = normalizeIpv4(lamp.getIp());
        if (camIp == null) {
            throw new ServiceException("摄像头 IP 缺失或格式无效");
        }
        if (lampIp == null) {
            throw new ServiceException("目标灯 IP 缺失或格式无效");
        }

        TrackingSession existing = activeByCam.get(cam.getChipId());
        if (existing != null) {
            if (sameChipId(existing.lampChipId(), lamp.getChipId())) {
                return status(existing.sessionId(), existing.camChipId(), existing.lampChipId(), existing.targetIndex(),
                        "tracking", "tracking session already active", existing.storeId());
            }
            stopSession(existing, "tracking target changed", true);
        }

        String sessionId = UUID.randomUUID().toString();
        ObjectNode lampCommand = objectMapper.createObjectNode();
        lampCommand.put("type", "lampTrackingStart");
        lampCommand.put("protocolVersion", TRACKING_PROTOCOL_VERSION);
        lampCommand.put("sessionId", sessionId);
        lampCommand.put("lampChipId", lamp.getChipId());
        lampCommand.put("camChipId", cam.getChipId());
        lampCommand.put("camIp", camIp);
        lampCommand.put("udpPort", TRACKING_UDP_PORT);

        if (!deviceSessionManager.sendToDevice(lamp.getChipId(), lampCommand.toString())) {
            throw new ServiceException("目标灯追踪会话下发失败");
        }

        ObjectNode camCommand = objectMapper.createObjectNode();
        camCommand.put("type", "cameraStartTracking");
        camCommand.put("protocolVersion", TRACKING_PROTOCOL_VERSION);
        camCommand.put("sessionId", sessionId);
        camCommand.put("camChipId", cam.getChipId());
        camCommand.put("targetChipId", lamp.getChipId());
        camCommand.put("lampIp", lampIp);
        camCommand.put("udpPort", TRACKING_UDP_PORT);
        camCommand.put("transport", "udp");

        if (!deviceSessionManager.sendToDevice(cam.getChipId(), camCommand.toString())) {
            sendLampStop(lamp.getChipId(), cam.getChipId(), sessionId, "camera start failed");
            throw new ServiceException("摄像头追踪指令下发失败");
        }

        TrackingSession session = new TrackingSession(
                sessionId,
                cam.getChipId(),
                lamp.getChipId(),
                targetIndex,
                cam.getStoreId(),
                source
        );
        activeByCam.put(cam.getChipId(), session);
        activeCamByLamp.put(lamp.getChipId(), cam.getChipId());
        return status(sessionId, cam.getChipId(), lamp.getChipId(), targetIndex,
                "armed", successMessage, cam.getStoreId());
    }

    private void stopAutoSessionForLamp(String lampChipId, String reason) {
        String camChipId = activeCamByLamp.get(lampChipId);
        if (camChipId == null) {
            return;
        }
        TrackingSession session = activeByCam.get(camChipId);
        if (session != null && session.source() == TrackingSource.AUTO_TOF) {
            stopSession(session, reason, true);
        }
    }

    private void stopSession(TrackingSession session, String reason, boolean pushStopped) {
        if (session == null || !activeByCam.remove(session.camChipId(), session)) {
            return;
        }
        activeCamByLamp.remove(session.lampChipId(), session.camChipId());
        sendLampStop(session.lampChipId(), session.camChipId(), session.sessionId(), reason);
        sendCameraStop(session.camChipId(), session.lampChipId(), session.sessionId(), reason);
        if (pushStopped) {
            status(session.sessionId(), session.camChipId(), session.lampChipId(), session.targetIndex(),
                    "stopped", reason, session.storeId());
        }
    }

    private void sendLampStop(String lampChipId, String camChipId, String sessionId, String reason) {
        if (!deviceSessionManager.isOnline(lampChipId)) {
            return;
        }
        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "lampTrackingStop");
        command.put("protocolVersion", TRACKING_PROTOCOL_VERSION);
        command.put("sessionId", sessionId);
        command.put("lampChipId", lampChipId);
        command.put("camChipId", camChipId);
        command.put("reason", reason == null ? "tracking stopped" : reason);
        deviceSessionManager.sendToDevice(lampChipId, command.toString());
    }

    private void sendCameraStop(String camChipId, String lampChipId, String sessionId, String reason) {
        if (!deviceSessionManager.isOnline(camChipId)) {
            return;
        }
        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "cameraTrackingStop");
        command.put("protocolVersion", TRACKING_PROTOCOL_VERSION);
        command.put("sessionId", sessionId);
        command.put("camChipId", camChipId);
        command.put("targetChipId", lampChipId);
        command.put("reason", reason == null ? "tracking stopped" : reason);
        deviceSessionManager.sendToDevice(camChipId, command.toString());
    }

    private TrackingSession findSession(DeviceTrackingStatusReqVO reqVO) {
        String camChipId = normalizeChipId(reqVO.getCamChipId());
        if (camChipId == null && "cam".equalsIgnoreCase(reqVO.getRole())) {
            camChipId = normalizeChipId(reqVO.getChipId());
        }
        if (camChipId != null) {
            return activeByCam.get(camChipId);
        }
        String lampChipId = normalizeChipId(reqVO.getLampChipId());
        if (lampChipId == null && "lamp".equalsIgnoreCase(reqVO.getRole())) {
            lampChipId = normalizeChipId(reqVO.getChipId());
        }
        String mappedCam = lampChipId == null ? null : activeCamByLamp.get(lampChipId);
        return mappedCam == null ? null : activeByCam.get(mappedCam);
    }

    private DeviceTrackingStatusRespVO status(
            String sessionId,
            String camChipId,
            String lampChipId,
            Integer targetIndex,
            String trackingStatus,
            String message,
            Long storeId) {
        DeviceTrackingStatusRespVO resp = new DeviceTrackingStatusRespVO();
        resp.setChipId(camChipId);
        resp.setRole("cam");
        resp.setTrackingStatus(trackingStatus);
        resp.setSessionId(sessionId);
        resp.setCamChipId(camChipId);
        resp.setLampChipId(lampChipId);
        resp.setTargetIndex(targetIndex);
        resp.setMessage(message);
        resp.setUpdateTime(LocalDateTime.now());
        webSocketPushService.pushTrackingStatus(resp, storeId);
        return resp;
    }

    private DeviceDO requireCam(String chipId) {
        DeviceDO device = requireDevice(chipId);
        if (!DeviceTypeUtil.isCam(device.getDeviceType())) {
            throw new ServiceException("设备不是 cam");
        }
        return device;
    }

    private DeviceDO requireLamp(String chipId) {
        DeviceDO device = requireDevice(chipId);
        if (!DeviceTypeUtil.isLampLike(device.getDeviceType())) {
            throw new ServiceException("目标设备必须是 lamp 或 camlamp");
        }
        return device;
    }

    private DeviceDO requireDevice(String chipId) {
        String normalized = normalizeChipId(chipId);
        if (normalized == null) {
            throw new ServiceException("chipId 不能为空");
        }
        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, normalized)
                        .last("limit 1")
        );
        if (device == null) {
            throw new ServiceException("设备不存在");
        }
        return device;
    }

    private void requireSameStore(DeviceDO left, DeviceDO right) {
        if (left.getStoreId() == null || right.getStoreId() == null
                || !left.getStoreId().equals(right.getStoreId())) {
            throw new ServiceException("设备不属于同一门店");
        }
    }

    private String normalizeChipId(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeStatus(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean sameChipId(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private String normalizeIpv4(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.trim();
        String[] segments = candidate.split("\\.", -1);
        if (segments.length != 4) {
            return null;
        }
        for (String segment : segments) {
            if (segment.isBlank() || !segment.chars().allMatch(Character::isDigit)) {
                return null;
            }
            try {
                int octet = Integer.parseInt(segment);
                if (octet < 0 || octet > 255) {
                    return null;
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return candidate;
    }

    private enum TrackingSource {
        AUTO_TOF,
        MANUAL
    }

    private record TrackingSession(
            String sessionId,
            String camChipId,
            String lampChipId,
            int targetIndex,
            Long storeId,
            TrackingSource source) {
    }
}
