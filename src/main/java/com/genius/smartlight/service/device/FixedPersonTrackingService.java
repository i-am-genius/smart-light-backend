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
import com.genius.smartlight.vo.device.DeviceCamCaptureConfigVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureTargetVO;
import com.genius.smartlight.vo.device.DeviceCamGlobalTrackingControlReqVO;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
    private final Map<String, GlobalTrackingSession> activeGlobalByCam = new ConcurrentHashMap<>();
    private final Map<String, String> activeGlobalCamByLamp = new ConcurrentHashMap<>();

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
            GlobalTrackingSession globalSession = activeGlobalByCam.get(camChipId);
            if (globalSession != null) {
                stopGlobalSession(globalSession, "manual tracking stopped", false);
                return globalStatus(globalSession, "stopped", "manual tracking stopped");
            }
            DeviceDO cam = requireCam(camChipId);
            return status(null, cam.getChipId(), reqVO.getTargetChipId(), reqVO.getTargetIndex(),
                    "stopped", "no active tracking session", cam.getStoreId());
        }
        stopSession(session, "manual tracking stopped", true);
        return status(session.sessionId(), session.camChipId(), session.lampChipId(), session.targetIndex(),
                "stopped", "manual tracking stopped", session.storeId());
    }

    public synchronized DeviceTrackingStatusRespVO startGlobal(DeviceCamGlobalTrackingControlReqVO reqVO) {
        DeviceDO cam = requireCam(reqVO.getCamChipId());
        if (!deviceSessionManager.isOnline(cam.getChipId())) {
            throw new ServiceException("摄像头离线，无法开始全局追踪");
        }
        String camIp = normalizeIpv4(cam.getIp());
        if (camIp == null) {
            throw new ServiceException("摄像头 IP 缺失或格式无效");
        }

        List<GlobalTrackingTarget> targets = resolveGlobalTargets(cam);
        GlobalTrackingSession existing = activeGlobalByCam.get(cam.getChipId());
        if (existing != null && sameGlobalTargets(existing.targets(), targets)) {
            return globalStatus(existing, "tracking", "global tracking session already active");
        }
        if (existing != null) {
            stopGlobalSession(existing, "global tracking targets changed", true);
        }
        TrackingSession singleSession = activeByCam.get(cam.getChipId());
        if (singleSession != null) {
            stopSession(singleSession, "switched to global tracking", true);
        }

        GlobalTrackingSession session = new GlobalTrackingSession(
                UUID.randomUUID().toString(),
                cam.getChipId(),
                cam.getStoreId(),
                targets
        );
        activeGlobalByCam.put(cam.getChipId(), session);
        targets.forEach(target -> activeGlobalCamByLamp.put(target.lampChipId(), cam.getChipId()));

        try {
            for (GlobalTrackingTarget target : targets) {
                ObjectNode lampCommand = createLampStartCommand(
                        session.sessionId(),
                        cam.getChipId(),
                        camIp,
                        target.lampChipId(),
                        target.targetIndex(),
                        "global"
                );
                if (!deviceSessionManager.sendToDevice(target.lampChipId(), lampCommand.toString())) {
                    throw new ServiceException("目标灯 " + target.lampChipId() + " 追踪会话下发失败");
                }
            }

            ObjectNode camCommand = objectMapper.createObjectNode();
            camCommand.put("type", "cameraStartTracking");
            camCommand.put("protocolVersion", TRACKING_PROTOCOL_VERSION);
            camCommand.put("trackingMode", "global");
            camCommand.put("sessionId", session.sessionId());
            camCommand.put("camChipId", cam.getChipId());
            camCommand.put("transport", "udp");
            var targetArray = camCommand.putArray("targets");
            for (GlobalTrackingTarget target : targets) {
                ObjectNode targetNode = targetArray.addObject();
                targetNode.put("targetIndex", target.targetIndex());
                targetNode.put("targetChipId", target.lampChipId());
                targetNode.put("lampIp", target.lampIp());
                targetNode.put("udpPort", TRACKING_UDP_PORT);
            }
            if (!deviceSessionManager.sendToDevice(cam.getChipId(), camCommand.toString())) {
                throw new ServiceException("摄像头全局追踪指令下发失败");
            }
        } catch (RuntimeException exception) {
            stopGlobalSession(session, "global tracking start failed", false);
            throw exception;
        }

        return globalStatus(session, "armed", "global tracking started");
    }

    public synchronized DeviceTrackingStatusRespVO stopGlobal(DeviceCamGlobalTrackingControlReqVO reqVO) {
        String camChipId = normalizeChipId(reqVO.getCamChipId());
        if (camChipId == null) {
            throw new ServiceException("camChipId 不能为空");
        }
        DeviceDO cam = requireCam(camChipId);
        captureConfigService.getForCurrentStore(cam.getChipId());
        GlobalTrackingSession session = activeGlobalByCam.get(camChipId);
        if (session == null) {
            return globalStatus(null, cam.getChipId(), cam.getStoreId(), List.of(),
                    "stopped", "no active global tracking session");
        }
        stopGlobalSession(session, "global tracking stopped manually", false);
        return globalStatus(session, "stopped", "global tracking stopped manually");
    }

    public void onTrackingStatus(DeviceTrackingStatusReqVO reqVO) {
        if (reqVO == null) {
            return;
        }
        String trackingStatus = normalizeStatus(reqVO.getTrackingStatus());
        if (trackingStatus == null) {
            return;
        }
        GlobalTrackingSession globalSession = findGlobalSession(reqVO);
        if (globalSession != null) {
            if (reqVO.getSessionId() != null
                    && !reqVO.getSessionId().isBlank()
                    && !globalSession.sessionId().equals(reqVO.getSessionId().trim())) {
                log.debug("ignore stale global tracking status, sessionId={}, activeSession={}",
                        reqVO.getSessionId(), globalSession.sessionId());
                return;
            }
            if (Set.of("error", "lost", "stopped", "timeout").contains(trackingStatus)) {
                stopGlobalSession(globalSession, "global tracking " + trackingStatus, true);
                return;
            }
            if (Set.of("armed", "tracking").contains(trackingStatus)) {
                globalStatus(globalSession, trackingStatus, reqVO.getMessage());
            }
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
        activeGlobalByCam.values().stream()
                .filter(session -> sameChipId(normalized, session.camChipId())
                        || session.targets().stream()
                                .anyMatch(target -> sameChipId(normalized, target.lampChipId())))
                .findFirst()
                .ifPresent(session -> stopGlobalSession(session, "global tracking device offline", true));
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
        if (activeGlobalByCam.containsKey(cam.getChipId())) {
            return;
        }
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

        GlobalTrackingSession globalSession = activeGlobalByCam.get(cam.getChipId());
        if (globalSession != null) {
            stopGlobalSession(globalSession, "switched to single-target tracking", true);
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
        ObjectNode lampCommand = createLampStartCommand(
                sessionId, cam.getChipId(), camIp, lamp.getChipId(), targetIndex, "single");

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

    private void stopGlobalSession(GlobalTrackingSession session, String reason, boolean pushStopped) {
        if (session == null || !activeGlobalByCam.remove(session.camChipId(), session)) {
            return;
        }
        for (GlobalTrackingTarget target : session.targets()) {
            activeGlobalCamByLamp.remove(target.lampChipId(), session.camChipId());
            sendLampStop(target.lampChipId(), session.camChipId(), session.sessionId(), reason);
        }
        sendGlobalCameraStop(session, reason);
        if (pushStopped) {
            globalStatus(session, "stopped", reason);
        }
    }

    private ObjectNode createLampStartCommand(
            String sessionId,
            String camChipId,
            String camIp,
            String lampChipId,
            int targetIndex,
            String trackingMode) {
        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "lampTrackingStart");
        command.put("protocolVersion", TRACKING_PROTOCOL_VERSION);
        command.put("trackingMode", trackingMode);
        command.put("sessionId", sessionId);
        command.put("lampChipId", lampChipId);
        command.put("camChipId", camChipId);
        command.put("camIp", camIp);
        command.put("targetIndex", targetIndex);
        command.put("udpPort", TRACKING_UDP_PORT);
        return command;
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

    private void sendGlobalCameraStop(GlobalTrackingSession session, String reason) {
        if (!deviceSessionManager.isOnline(session.camChipId())) {
            return;
        }
        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "cameraTrackingStop");
        command.put("protocolVersion", TRACKING_PROTOCOL_VERSION);
        command.put("trackingMode", "global");
        command.put("sessionId", session.sessionId());
        command.put("camChipId", session.camChipId());
        command.put("reason", reason == null ? "global tracking stopped" : reason);
        var targets = command.putArray("targets");
        session.targets().forEach(target -> {
            ObjectNode node = targets.addObject();
            node.put("targetIndex", target.targetIndex());
            node.put("targetChipId", target.lampChipId());
            node.put("lampIp", target.lampIp());
            node.put("udpPort", TRACKING_UDP_PORT);
        });
        deviceSessionManager.sendToDevice(session.camChipId(), command.toString());
    }

    private GlobalTrackingSession findGlobalSession(DeviceTrackingStatusReqVO reqVO) {
        String camChipId = normalizeChipId(reqVO.getCamChipId());
        if (camChipId == null && "cam".equalsIgnoreCase(reqVO.getRole())) {
            camChipId = normalizeChipId(reqVO.getChipId());
        }
        if (camChipId != null) {
            GlobalTrackingSession session = activeGlobalByCam.get(camChipId);
            if (session != null) {
                return session;
            }
        }
        String lampChipId = normalizeChipId(reqVO.getLampChipId());
        if (lampChipId == null && "lamp".equalsIgnoreCase(reqVO.getRole())) {
            lampChipId = normalizeChipId(reqVO.getChipId());
        }
        String mappedCam = lampChipId == null ? null : activeGlobalCamByLamp.get(lampChipId);
        return mappedCam == null ? null : activeGlobalByCam.get(mappedCam);
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
        resp.setTrackingMode("single");
        resp.setSessionId(sessionId);
        resp.setCamChipId(camChipId);
        resp.setLampChipId(lampChipId);
        resp.setTargetIndex(targetIndex);
        resp.setTargetChipIds(lampChipId == null ? List.of() : List.of(lampChipId));
        resp.setMessage(message);
        resp.setUpdateTime(LocalDateTime.now());
        webSocketPushService.pushTrackingStatus(resp, storeId);
        return resp;
    }

    private DeviceTrackingStatusRespVO globalStatus(
            GlobalTrackingSession session,
            String trackingStatus,
            String message) {
        return globalStatus(
                session == null ? null : session.sessionId(),
                session == null ? null : session.camChipId(),
                session == null ? null : session.storeId(),
                session == null ? List.of() : session.targets(),
                trackingStatus,
                message
        );
    }

    private DeviceTrackingStatusRespVO globalStatus(
            String sessionId,
            String camChipId,
            Long storeId,
            List<GlobalTrackingTarget> targets,
            String trackingStatus,
            String message) {
        DeviceTrackingStatusRespVO resp = new DeviceTrackingStatusRespVO();
        resp.setChipId(camChipId);
        resp.setRole("cam");
        resp.setTrackingStatus(trackingStatus);
        resp.setTrackingMode("global");
        resp.setSessionId(sessionId);
        resp.setCamChipId(camChipId);
        resp.setTargetChipIds(targets.stream().map(GlobalTrackingTarget::lampChipId).toList());
        resp.setMessage(message);
        resp.setUpdateTime(LocalDateTime.now());
        webSocketPushService.pushTrackingStatus(resp, storeId);
        return resp;
    }

    private List<GlobalTrackingTarget> resolveGlobalTargets(DeviceDO cam) {
        DeviceCamCaptureConfigVO config = captureConfigService.getForCurrentStore(cam.getChipId());
        List<DeviceCamCaptureTargetVO> configuredTargets = config == null || config.getTargets() == null
                ? List.of()
                : config.getTargets().stream()
                        .filter(target -> target != null && target.getIndex() != null)
                        .sorted(Comparator.comparingInt(DeviceCamCaptureTargetVO::getIndex))
                        .toList();
        if (configuredTargets.size() != 3) {
            throw new ServiceException("全局追踪要求完整配置三个目标灯");
        }

        HashSet<String> uniqueLampIds = new HashSet<>();
        List<GlobalTrackingTarget> targets = new ArrayList<>();
        for (int expectedIndex = 1; expectedIndex <= 3; expectedIndex++) {
            final int targetIndex = expectedIndex;
            DeviceCamCaptureTargetVO configured = configuredTargets.stream()
                    .filter(target -> target.getIndex() == targetIndex)
                    .findFirst()
                    .orElseThrow(() -> new ServiceException("拍摄目标 " + targetIndex + " 未配置"));
            String lampChipId = normalizeChipId(configured.getLampChipId());
            if (lampChipId == null) {
                throw new ServiceException("拍摄目标 " + targetIndex + " 未绑定目标灯");
            }
            if (!uniqueLampIds.add(lampChipId.toUpperCase(Locale.ROOT))) {
                throw new ServiceException("全局追踪的三个目标灯不能重复");
            }

            DeviceDO lamp = requireLamp(lampChipId);
            requireSameStore(cam, lamp);
            if (!deviceSessionManager.isOnline(lamp.getChipId())) {
                throw new ServiceException("目标灯 " + lamp.getChipId() + " 离线，无法开始全局追踪");
            }
            String lampIp = normalizeIpv4(lamp.getIp());
            if (lampIp == null) {
                throw new ServiceException("目标灯 " + lamp.getChipId() + " IP 缺失或格式无效");
            }
            targets.add(new GlobalTrackingTarget(targetIndex, lamp.getChipId(), lampIp));
        }
        return List.copyOf(targets);
    }

    private boolean sameGlobalTargets(
            List<GlobalTrackingTarget> left,
            List<GlobalTrackingTarget> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            GlobalTrackingTarget a = left.get(index);
            GlobalTrackingTarget b = right.get(index);
            if (a.targetIndex() != b.targetIndex()
                    || !sameChipId(a.lampChipId(), b.lampChipId())
                    || !a.lampIp().equals(b.lampIp())) {
                return false;
            }
        }
        return true;
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

    private record GlobalTrackingTarget(
            int targetIndex,
            String lampChipId,
            String lampIp) {
    }

    private record GlobalTrackingSession(
            String sessionId,
            String camChipId,
            Long storeId,
            List<GlobalTrackingTarget> targets) {
    }
}
