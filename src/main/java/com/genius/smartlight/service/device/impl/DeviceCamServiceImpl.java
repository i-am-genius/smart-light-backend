package com.genius.smartlight.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.genius.smartlight.common.DeviceTypeUtil;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.PersonFlowRecordDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.DurationRecordMapper;
import com.genius.smartlight.service.ai.AiService;
import com.genius.smartlight.service.device.DeviceCamService;
import com.genius.smartlight.service.device.SliderMotionStateService;
import com.genius.smartlight.service.personflow.PersonFlowRecordService;
import com.genius.smartlight.service.store.CurrentStoreService;
import com.genius.smartlight.vo.device.DeviceCamCaptureBatchReqVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureBatchRespVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskReqVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskRespVO;
import com.genius.smartlight.vo.device.DeviceCamPresenceReqVO;
import com.genius.smartlight.vo.device.DeviceCamPresenceRespVO;
import com.genius.smartlight.vo.device.DeviceCamRoiConfigVO;
import com.genius.smartlight.vo.device.DeviceCamRoiItemVO;
import com.genius.smartlight.vo.device.DeviceCamSliderMoveTimeVO;
import com.genius.smartlight.vo.device.DeviceCamStatusReqVO;
import com.genius.smartlight.vo.device.DeviceCamStatusRespVO;
import com.genius.smartlight.vo.device.DeviceCamTrackingControlReqVO;
import com.genius.smartlight.vo.device.DeviceLampClothStateReqVO;
import com.genius.smartlight.vo.device.DeviceLampClothStateRespVO;
import com.genius.smartlight.vo.device.DeviceLampProximityStateReqVO;
import com.genius.smartlight.vo.device.DeviceLampProximityStateRespVO;
import com.genius.smartlight.vo.device.DeviceSliderStatusReqVO;
import com.genius.smartlight.vo.device.DeviceTrackingStatusReqVO;
import com.genius.smartlight.vo.device.DeviceTrackingStatusRespVO;
import com.genius.smartlight.websocket.DeviceSessionManager;
import com.genius.smartlight.websocket.WebSocketPushService;
import com.genius.smartlight.websocket.WsMessage;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceCamServiceImpl implements DeviceCamService {

    private static final Path CAM_CONFIG_DIR = Path.of("data", "cam-config").toAbsolutePath().normalize();
    private static final Path CAM_UPLOAD_DIR = Path.of("data", "cam-upload").toAbsolutePath().normalize();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int CAPTURE_TIMEOUT_SECONDS = 45;
    private static final int BATCH_TARGET_COUNT = 3;
    private static final double SLIDER_STATE_RECONCILIATION_TOLERANCE_MM = 0.05D;

    private final DeviceMapper deviceMapper;
    private final CurrentStoreService currentStoreService;
    private final WebSocketPushService webSocketPushService;
    private final DeviceSessionManager deviceSessionManager;
    private final PersonFlowRecordService personFlowRecordService;
    private final DurationRecordMapper durationRecordMapper;
    private final AiService aiService;
    private final SliderMotionStateService sliderMotionStateService;
    private final ObjectMapper objectMapper;
    @Autowired
    @Qualifier("deviceRestTemplate")
    private RestTemplate deviceRestTemplate;

    @Value("${device.cam.lamp-ip-push-path:/lamp-ip}")
    private String lampIpPushPath;

    private final Map<String, DeviceCamPresenceRespVO> presenceCache = new ConcurrentHashMap<>();
    private final Map<String, DeviceCamStatusRespVO> statusCache = new ConcurrentHashMap<>();
    private final Map<String, DeviceCamCaptureTaskRespVO> taskCache = new ConcurrentHashMap<>();
    private final Map<String, DeviceCamCaptureBatchRespVO> batchCache = new ConcurrentHashMap<>();
    private final Map<String, CaptureBatchContext> batchContexts = new ConcurrentHashMap<>();
    private final Map<String, String> captureBatchByTask = new ConcurrentHashMap<>();
    private final Map<String, DeviceLampClothStateRespVO> clothStateCache = new ConcurrentHashMap<>();
    private final Map<String, DeviceLampProximityStateRespVO> proximityStateCache = new ConcurrentHashMap<>();
    private final Map<Long, String> garmentDetectionStatusByStore = new ConcurrentHashMap<>();
    private final Map<Long, String> automaticDetectionBatchByStore = new ConcurrentHashMap<>();
    private final Set<String> failedAutomaticDetectionBatches = ConcurrentHashMap.newKeySet();
    private final Map<String, DeviceTrackingStatusRespVO> trackingCache = new ConcurrentHashMap<>();
    private final Map<String, TrackingSession> activeTrackingByCam = new ConcurrentHashMap<>();
    private final Map<String, PresenceDurationState> presenceDurationState = new ConcurrentHashMap<>();
    private final Map<String, String> captureUploadTokens = new ConcurrentHashMap<>();
    private final Map<String, PendingCaptureMotion> pendingCaptureMotions = new ConcurrentHashMap<>();
    private final Map<String, String> activeCaptureTaskBySliderLamp = new ConcurrentHashMap<>();
    private final Map<String, String> activeCaptureTaskByCam = new ConcurrentHashMap<>();
    private final Map<String, String> activeCaptureTaskByCaptureController = new ConcurrentHashMap<>();
    private final Map<String, String> captureSliderLampByTask = new ConcurrentHashMap<>();
    private final Map<String, String> captureControllerByTask = new ConcurrentHashMap<>();
    private final Map<String, CollisionGuardSession> retainedCaptureGuards = new ConcurrentHashMap<>();
    private final Map<String, PendingSingleReturn> pendingSingleReturns = new ConcurrentHashMap<>();
    private final Map<String, PendingBatchReturn> pendingBatchReturns = new ConcurrentHashMap<>();
    private final ScheduledExecutorService captureTimeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "cam-capture-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService captureAiExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "cam-capture-ai");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public DeviceCamRoiConfigVO getRoiConfig(String camChipId) {
        DeviceDO cam = requireCamForCurrentStore(camChipId);
        return readRoiConfig(cam.getChipId());
    }

    @Override
    public DeviceCamRoiConfigVO getRoiConfigForDevice(String camChipId) {
        DeviceDO cam = requireCam(camChipId);
        return readRoiConfig(cam.getChipId());
    }

    @Override
    public void pushCaptureControllerConfigForDevice(String captureControllerChipId) {
        if (!notBlank(captureControllerChipId) || !Files.isDirectory(CAM_CONFIG_DIR)) {
            return;
        }
        try (var paths = Files.list(CAM_CONFIG_DIR)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                try {
                    DeviceCamRoiConfigVO saved = objectMapper.readValue(
                            path.toFile(),
                            DeviceCamRoiConfigVO.class
                    );
                    if (!notBlank(saved.getCamChipId())) {
                        continue;
                    }
                    DeviceCamRoiConfigVO normalized = normalizeConfig(saved.getCamChipId(), saved);
                    if (sameChipId(normalized.getCaptureControllerChipId(), captureControllerChipId)) {
                        pushCaptureControllerConfig(normalized);
                        return;
                    }
                } catch (IOException | RuntimeException e) {
                    log.warn("read capture controller binding config failed, path={}", path, e);
                }
            }
        } catch (IOException e) {
            log.warn("scan capture controller binding config failed, captureControllerChipId={}",
                    captureControllerChipId, e);
        }
    }

    @Override
    public DeviceCamRoiConfigVO saveRoiConfig(String camChipId, DeviceCamRoiConfigVO config) {
        DeviceDO cam = requireCamForCurrentStore(camChipId);
        DeviceCamRoiConfigVO normalized = normalizeConfig(cam.getChipId(), config);
        if (notBlank(normalized.getSliderLampChipId())) {
            requireLampLikeForCurrentStore(normalized.getSliderLampChipId());
        }
        if (notBlank(normalized.getCaptureControllerChipId())) {
            requireCaptureControllerForCurrentStore(normalized.getCaptureControllerChipId());
        }
        Map<String, DeviceDO> targetDevices = new LinkedHashMap<>();
        for (DeviceCamRoiItemVO roi : normalized.getRois()) {
            if (notBlank(roi.getTargetChipId())) {
                DeviceDO target = requireLampLikeForCurrentStore(roi.getTargetChipId());
                targetDevices.put(target.getChipId(), target);
            }
        }
        writeRoiConfig(normalized);
        pushRoiConfigToCam(cam.getChipId(), normalized);
        pushCaptureControllerConfig(normalized);
        pushLampIpsToCamByHttp(cam, normalized, targetDevices);
        webSocketPushService.pushCamStatus(Map.of(
                "camChipId", cam.getChipId(),
                "roiConfigured", Boolean.TRUE.equals(normalized.getConfigured()),
                "config", normalized
        ), cam.getStoreId());
        return normalized;
    }

    private void pushRoiConfigToCam(String camChipId, DeviceCamRoiConfigVO config) {
        try {
            String payload = objectMapper.writeValueAsString(WsMessage.of("cameraRoiConfig", config));
            boolean sent = webSocketPushService.pushRawToDevice(camChipId, payload);
            if (!sent) {
                log.warn("push cam roi config skipped or failed, camChipId={}", camChipId);
            }
        } catch (Exception e) {
            log.warn("push cam roi config failed, camChipId={}", camChipId, e);
        }
    }

    private void pushCaptureControllerConfig(DeviceCamRoiConfigVO config) {
        if (config == null || !notBlank(config.getCaptureControllerChipId())) {
            return;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "captureControllerConfig");
        payload.put("camChipId", config.getCamChipId());
        payload.put("captureControllerChipId", config.getCaptureControllerChipId());
        payload.put("flowUploadEnabled", Boolean.TRUE.equals(config.getFlowUploadEnabled()));
        payload.put("flowUploadIntervalSeconds", config.getFlowUploadIntervalSeconds());
        var captureAreas = payload.putArray("captureAreas");
        for (DeviceCamRoiItemVO roi : config.getRois()) {
            ObjectNode area = captureAreas.addObject();
            area.put("targetIndex", roi.getTargetIndex());
            ObjectNode garmentPreset = area.putObject("garmentCapturePreset");
            garmentPreset.put("pan", roi.getGarmentCapturePan());
            garmentPreset.put("tilt", roi.getGarmentCaptureTilt());
            ObjectNode personPreset = area.putObject("personCapturePreset");
            personPreset.put("pan", roi.getPersonCapturePan());
            personPreset.put("tilt", roi.getPersonCaptureTilt());
        }
        // 兼容尚未升级到分区预置协议的拍照控制器；区域任务本身仍会携带精确角度。
        DeviceCamRoiItemVO fallbackArea = config.getRois().stream().findFirst().orElse(null);
        if (fallbackArea != null) {
            ObjectNode garmentPreset = payload.putObject("garmentCapturePreset");
            garmentPreset.put("pan", fallbackArea.getGarmentCapturePan());
            garmentPreset.put("tilt", fallbackArea.getGarmentCaptureTilt());
            ObjectNode personPreset = payload.putObject("personCapturePreset");
            personPreset.put("pan", fallbackArea.getPersonCapturePan());
            personPreset.put("tilt", fallbackArea.getPersonCaptureTilt());
        }
        payload.put(
                "flowUploadUrl",
                "/device/cam/flow-photo?camChipId=" + config.getCamChipId()
                        + "&captureControllerChipId=" + config.getCaptureControllerChipId()
        );
        try {
            boolean sent = deviceSessionManager.sendToDevice(
                    config.getCaptureControllerChipId(),
                    payload.toString()
            );
            if (!sent) {
                log.warn("push capture controller config skipped or failed, captureControllerChipId={}",
                        config.getCaptureControllerChipId());
            }
        } catch (RuntimeException e) {
            log.warn("push capture controller config failed, captureControllerChipId={}",
                    config.getCaptureControllerChipId(), e);
        }
    }

    private void pushLampIpsToCamByHttp(
            DeviceDO cam,
            DeviceCamRoiConfigVO config,
            Map<String, DeviceDO> targetDevices) {
        if (!notBlank(cam.getIp())) {
            log.warn("push lamp ips to cam skipped: cam ip is blank, camChipId={}", cam.getChipId());
            return;
        }

        List<String> lampIps = targetDevices.values().stream()
                .map(DeviceDO::getIp)
                .map(this::normalizeLampIp)
                .filter(ip -> ip != null)
                .distinct()
                .toList();
        Map<String, Object> payload = buildLampIpPayload(config, targetDevices);

        try {
            URI uri = URI.create(buildDeviceUrl(cam.getIp(), lampIpPushPath));
            deviceRestTemplate.postForEntity(uri, payload, String.class);
            log.info("push lamp ips to cam success, camChipId={}, lampIps={}", cam.getChipId(), lampIps);
        } catch (Exception e) {
            log.warn("push lamp ips to cam failed, camChipId={}, camIp={}, lampIps={}",
                    cam.getChipId(), cam.getIp(), lampIps, e);
        }
    }

    Map<String, Object> buildLampIpPayload(
            DeviceCamRoiConfigVO config,
            Map<String, DeviceDO> targetDevices) {
        List<String> lampIps = targetDevices.values().stream()
                .map(DeviceDO::getIp)
                .map(this::normalizeLampIp)
                .filter(ip -> ip != null)
                .distinct()
                .toList();
        List<DeviceCamRoiItemVO> rois = config.getRois() == null ? List.of() : config.getRois();
        List<Map<String, Object>> targets = rois.stream()
                .map(roi -> {
                    DeviceDO target = targetDevices.values().stream()
                            .filter(device -> sameChipId(device.getChipId(), roi.getTargetChipId()))
                            .findFirst()
                            .orElse(null);
                    String lampIp = target == null ? null : normalizeLampIp(target.getIp());
                    if (lampIp == null) {
                        return null;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("targetIndex", roi.getTargetIndex());
                    item.put("targetChipId", target.getChipId());
                    item.put("lampIp", lampIp);
                    return item;
                })
                .filter(item -> item != null)
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lampIps", lampIps);
        payload.put("targets", targets);
        return payload;
    }

    private String buildDeviceUrl(String host, String path) {
        String base = host.trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "http://" + base;
        }
        String normalizedPath = notBlank(path) ? path.trim() : "/lamp-ip";
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return base + normalizedPath;
    }

    @Override
    public DeviceCamPresenceRespVO reportPresence(DeviceCamPresenceReqVO reqVO) {
        DeviceDO cam = requireCam(reqVO.getCamChipId());
        LocalDateTime now = LocalDateTime.now();
        DeviceCamPresenceRespVO resp = new DeviceCamPresenceRespVO();
        resp.setCamChipId(cam.getChipId());
        resp.setWorkStatus(defaultStatus(reqVO.getWorkStatus(), "monitoring"));
        resp.setConfigured(Boolean.TRUE.equals(readRoiConfig(cam.getChipId()).getConfigured()));
        resp.setPersonCount(reqVO.getPersonCount());
        resp.setConfidence(reqVO.getConfidence());
        resp.setAreas(reqVO.getAreas());
        resp.setUpdateTime(now);
        presenceCache.put(cam.getChipId(), resp);
        saveFlowRecordIfNeeded(cam, reqVO.getPersonCount(), reqVO.getConfidence(), reqVO.getDetectTime(), null);
        recordPresenceDurations(cam, reqVO.getAreas(), now);
        webSocketPushService.pushCamPresence(resp, cam.getStoreId());
        evaluateTrackingForCam(cam.getChipId());
        return resp;
    }

    @Override
    public DeviceCamPresenceRespVO getPresence(String camChipId) {
        requireCamForCurrentStore(camChipId);
        return presenceCache.getOrDefault(camChipId, emptyPresence(camChipId));
    }

    @Override
    public DeviceCamStatusRespVO reportStatus(DeviceCamStatusReqVO reqVO) {
        DeviceDO cam = requireCam(reqVO.getCamChipId());
        DeviceCamStatusRespVO resp = new DeviceCamStatusRespVO();
        resp.setCamChipId(cam.getChipId());
        resp.setWorkStatus(defaultStatus(reqVO.getWorkStatus(), "monitoring"));
        resp.setActiveTargetIndex(reqVO.getActiveTargetIndex());
        resp.setActiveTargetChipId(reqVO.getActiveTargetChipId());
        resp.setMessage(reqVO.getMessage());
        resp.setUpdateTime(LocalDateTime.now());
        statusCache.put(cam.getChipId(), resp);
        recoverTrackingCacheFromCamStatus(cam, resp);
        webSocketPushService.pushCamStatus(resp, cam.getStoreId());
        return resp;
    }

    @Override
    public DeviceCamStatusRespVO getStatus(String camChipId) {
        requireCamForCurrentStore(camChipId);
        return statusCache.getOrDefault(camChipId, defaultCamStatus(camChipId));
    }

    @Override
    public DeviceTrackingStatusRespVO startTrackingManually(DeviceCamTrackingControlReqVO reqVO) {
        DeviceDO cam = requireCamForCurrentStore(reqVO.getCamChipId());
        DeviceDO lamp = requireLampLikeForCurrentStore(reqVO.getTargetChipId());
        int targetIndex = normalizeTargetIndex(reqVO.getTargetIndex());
        DeviceCamRoiConfigVO config = readRoiConfig(cam.getChipId());
        requireConfiguredTrackingTarget(config, targetIndex, lamp.getChipId());

        if (!deviceSessionManager.isOnline(cam.getChipId())) {
            throw new ServiceException("摄像头离线，无法开始追踪");
        }
        if (!deviceSessionManager.isOnline(lamp.getChipId())) {
            throw new ServiceException("目标灯离线，无法开始追踪");
        }
        if (normalizeLampIp(lamp.getIp()) == null) {
            throw new ServiceException("目标灯 IP 缺失或格式无效");
        }

        String requestedKey = trackingKey(lamp.getChipId(), targetIndex);
        TrackingSession active = activeTrackingByCam.get(cam.getChipId());
        if (active != null && !requestedKey.equals(active.trackingKey())) {
            stopTrackingIfActive(cam.getChipId(), "manual tracking target changed");
        }

        TrackingCandidate candidate = trackingCandidate(cam.getChipId(), lamp.getChipId(), targetIndex);
        startTrackingIfNeeded(candidate, TrackingSource.MANUAL, "manual tracking started");
        DeviceTrackingStatusRespVO result = trackingCache.get(cam.getChipId());
        if (result == null || !"tracking".equals(result.getTrackingStatus())) {
            throw new ServiceException(result == null ? "追踪指令下发失败" : result.getMessage());
        }
        return result;
    }

    @Override
    public DeviceTrackingStatusRespVO stopTrackingManually(DeviceCamTrackingControlReqVO reqVO) {
        DeviceDO cam = requireCamForCurrentStore(reqVO.getCamChipId());
        DeviceDO lamp = requireLampLikeForCurrentStore(reqVO.getTargetChipId());
        int targetIndex = normalizeTargetIndex(reqVO.getTargetIndex());

        if (!deviceSessionManager.isOnline(cam.getChipId())) {
            throw new ServiceException("摄像头离线，无法停止追踪");
        }
        if (!deviceSessionManager.isOnline(lamp.getChipId())) {
            throw new ServiceException("目标灯离线，无法停止追踪");
        }

        activeTrackingByCam.remove(cam.getChipId());
        sendLampTrackingStop(lamp.getChipId(), cam.getChipId(), "manual tracking stopped", false);
        sendCameraReturnCenter(cam, "manual tracking stopped");

        TrackingCandidate candidate = trackingCandidate(cam.getChipId(), lamp.getChipId(), targetIndex);
        pushTracking(candidate, "stopped", "manual tracking stopped", cam.getStoreId());
        webSocketPushService.pushCamStatus(Map.of(
                "camChipId", cam.getChipId(),
                "workStatus", "returning_center",
                "message", "manual tracking stopped"
        ), cam.getStoreId());
        return trackingCache.get(cam.getChipId());
    }

    @Override
    public DeviceCamCaptureTaskRespVO createCaptureTask(DeviceCamCaptureTaskReqVO reqVO) {
        DeviceDO cam = requireCamForCurrentStore(reqVO.getCamChipId());
        String targetChipId = resolveTargetChipId(cam.getChipId(), reqVO.getTargetIndex(), reqVO.getTargetChipId());
        DeviceDO target = requireLampLikeForCurrentStore(targetChipId);
        int targetIndex = resolveCaptureTargetIndex(
                cam.getChipId(),
                reqVO.getTargetIndex(),
                target.getChipId()
        );
        DeviceCamRoiConfigVO config = readRoiConfig(cam.getChipId());
        DeviceDO captureController = requireCaptureControllerForCurrentStore(config);
        if (!deviceSessionManager.isOnline(captureController.getChipId())) {
            throw new ServiceException("拍照控制器离线，无法创建拍摄任务");
        }
        DeviceDO sliderLamp = requireSliderLampForCurrentStore(config);
        if (!deviceSessionManager.isOnline(sliderLamp.getChipId())) {
            throw new ServiceException("滑轨控制灯离线，无法执行滑轨对位");
        }
        double sliderTargetMm = resolveSliderPreset(config, targetIndex);
        TimedSliderMotion motionPlan = planSliderMotion(
                config,
                targetIndex,
                sliderLamp,
                sliderTargetMm
        );
        double singleReturnSliderMm = resolveSingleReturnSliderPreset(config, targetIndex);
        planSliderMotion(config, targetIndex, sliderLamp, singleReturnSliderMm);

        DeviceCamCaptureTaskRespVO task = new DeviceCamCaptureTaskRespVO();
        task.setTaskId(UUID.randomUUID().toString());
        task.setCamChipId(cam.getChipId());
        task.setTargetChipId(target.getChipId());
        task.setTargetIndex(targetIndex);
        task.setStatus("waiting_motion");
        task.setMessage("waiting for estimated slider motion time");
        task.setCreateTime(LocalDateTime.now());
        claimCaptureSlot(
                activeCaptureTaskBySliderLamp,
                sliderLamp.getChipId(),
                task.getTaskId(),
                "滑轨控制灯已有拍摄任务执行中"
        );
        try {
            claimCaptureSlot(activeCaptureTaskByCam, cam.getChipId(), task.getTaskId(), "摄像头已有拍摄任务执行中");
        } catch (RuntimeException e) {
            activeCaptureTaskBySliderLamp.remove(sliderLamp.getChipId(), task.getTaskId());
            throw e;
        }
        try {
            claimCaptureSlot(
                    activeCaptureTaskByCaptureController,
                    captureController.getChipId(),
                    task.getTaskId(),
                    "拍照控制器已有拍摄任务执行中"
            );
        } catch (RuntimeException e) {
            activeCaptureTaskByCam.remove(cam.getChipId(), task.getTaskId());
            activeCaptureTaskBySliderLamp.remove(sliderLamp.getChipId(), task.getTaskId());
            throw e;
        }
        captureSliderLampByTask.put(task.getTaskId(), sliderLamp.getChipId());
        captureControllerByTask.put(task.getTaskId(), captureController.getChipId());
        taskCache.put(task.getTaskId(), task);
        String uploadToken = UUID.randomUUID().toString().replace("-", "");
        captureUploadTokens.put(task.getTaskId(), uploadToken);
        DeviceCamRoiItemVO captureRoi = requireCaptureRoi(config, targetIndex);

        PendingCaptureMotion pending = new PendingCaptureMotion(
                cam.getChipId(),
                sliderLamp.getChipId(),
                captureController.getChipId(),
                captureRoi.getGarmentCapturePan(),
                captureRoi.getGarmentCaptureTilt(),
                target.getChipId(),
                targetIndex,
                sliderTargetMm,
                uploadToken,
                cam.getStoreId()
        );
        pendingCaptureMotions.put(task.getTaskId(), pending);

        CollisionGuardSession guardSession;
        try {
            guardSession = prepareCollisionGuards(config, motionPlan, task.getTaskId());
            retainCollisionGuards(task.getTaskId(), guardSession);
        } catch (RuntimeException e) {
            discardCaptureTask(task);
            throw e;
        }
        webSocketPushService.pushCamCaptureTask(task, cam.getStoreId());
        Runnable dispatch = () -> {
            try {
                sendSliderMotionCommand(sliderLamp.getChipId(), sliderTargetMm,
                        "camera_capture", task.getTaskId());
                beginSliderMotion(sliderLamp, motionPlan);
                scheduleTimedCapture(task.getTaskId(), motionPlan.delayMs());
            } catch (RuntimeException e) {
                releaseRetainedCollisionGuards(task.getTaskId());
                failCaptureTask(task, "motion_command_failed", e.getMessage(), cam.getStoreId());
            }
        };
        if (guardSession.parkDelayMs() > 0L) {
            captureTimeoutExecutor.schedule(dispatch, guardSession.parkDelayMs(), TimeUnit.MILLISECONDS);
        } else {
            dispatch.run();
        }
        return task;
    }

    @Override
    public DeviceCamCaptureBatchRespVO createCaptureBatch(DeviceCamCaptureBatchReqVO reqVO) {
        DeviceDO cam = requireCamForCurrentStore(reqVO.getCamChipId());
        return createCaptureBatchForCam(cam);
    }

    private DeviceCamCaptureBatchRespVO createCaptureBatchForCam(DeviceDO cam) {
        DeviceCamRoiConfigVO config = readRoiConfig(cam.getChipId());
        DeviceDO captureController = requireCaptureController(config);
        requireSameStore(cam, captureController);
        if (!deviceSessionManager.isOnline(captureController.getChipId())) {
            throw new ServiceException("拍照控制器离线，无法执行批量拍摄");
        }
        DeviceDO sliderLamp = requireSliderLamp(config);
        requireSameStore(cam, sliderLamp);
        if (!deviceSessionManager.isOnline(sliderLamp.getChipId())) {
            throw new ServiceException("滑轨控制灯离线，无法执行批量拍摄");
        }
        List<BatchCaptureTarget> targets = buildBatchCaptureTargets(config, cam.getStoreId());
        validateBatchMotionCalibration(config, sliderLamp, targets);
        String batchId = UUID.randomUUID().toString();
        DeviceCamCaptureBatchRespVO batch = new DeviceCamCaptureBatchRespVO();
        batch.setBatchId(batchId);
        batch.setCamChipId(cam.getChipId());
        batch.setStatus("running");
        batch.setMessage("batch capture started");
        batch.setCreateTime(LocalDateTime.now());

        claimCaptureSlot(
                activeCaptureTaskBySliderLamp,
                sliderLamp.getChipId(),
                batchId,
                "滑轨控制灯已有拍摄任务执行中"
        );
        try {
            claimCaptureSlot(activeCaptureTaskByCam, cam.getChipId(), batchId, "摄像头已有拍摄任务执行中");
        } catch (RuntimeException e) {
            activeCaptureTaskBySliderLamp.remove(sliderLamp.getChipId(), batchId);
            throw e;
        }
        try {
            claimCaptureSlot(
                    activeCaptureTaskByCaptureController,
                    captureController.getChipId(),
                    batchId,
                    "拍照控制器已有拍摄任务执行中"
            );
        } catch (RuntimeException e) {
            activeCaptureTaskByCam.remove(cam.getChipId(), batchId);
            activeCaptureTaskBySliderLamp.remove(sliderLamp.getChipId(), batchId);
            throw e;
        }
        captureSliderLampByTask.put(batchId, sliderLamp.getChipId());
        captureControllerByTask.put(batchId, captureController.getChipId());
        batchCache.put(batchId, batch);

        try {
            for (int index = 0; index < targets.size(); index++) {
                BatchCaptureTarget target = targets.get(index);
                DeviceCamCaptureTaskRespVO task = new DeviceCamCaptureTaskRespVO();
                task.setTaskId(UUID.randomUUID().toString());
                task.setBatchId(batchId);
                task.setSequence(index + 1);
                task.setCamChipId(cam.getChipId());
                task.setTargetChipId(target.targetChipId());
                task.setTargetIndex(target.targetIndex());
                task.setStatus("queued");
                task.setMessage("waiting for previous batch target");
                task.setCreateTime(LocalDateTime.now());
                batch.getTasks().add(task);
                taskCache.put(task.getTaskId(), task);
                captureBatchByTask.put(task.getTaskId(), batchId);
                captureUploadTokens.put(task.getTaskId(), UUID.randomUUID().toString().replace("-", ""));
            }

            double standbySliderMm = resolveStandbySliderPreset(config, targets);
            CaptureBatchContext context = new CaptureBatchContext(
                    batch,
                    targets,
                    sliderLamp.getChipId(),
                    captureController.getChipId(),
                    cam.getStoreId(),
                    config,
                    standbySliderMm
            );
            batchContexts.put(batchId, context);
            for (DeviceCamCaptureTaskRespVO queuedTask : batch.getTasks()) {
                webSocketPushService.pushCamCaptureTask(queuedTask, cam.getStoreId());
            }
            startBatchTarget(context, 0);
            return batch;
        } catch (RuntimeException e) {
            discardCaptureBatch(batchId);
            throw e;
        }
    }

    @Override
    public void reportSliderStatus(DeviceSliderStatusReqVO reqVO) {
        if (reqVO == null || !"arrived".equalsIgnoreCase(defaultStatus(reqVO.getStatus(), ""))) {
            return;
        }
        if (!notBlank(reqVO.getChipId()) || reqVO.getTargetMm() == null) {
            return;
        }
        try {
            DeviceDO sliderLamp = requireLampLike(reqVO.getChipId().trim());
            SliderMotionStateService.SliderStateSnapshot snapshot = sliderMotionStateService.getSnapshot(
                    sliderLamp.getChipId(),
                    sliderLamp.getStoreId()
            );
            if (Math.abs(snapshot.targetPositionMm() - reqVO.getTargetMm())
                    > SLIDER_STATE_RECONCILIATION_TOLERANCE_MM) {
                log.warn("stale slider status ignored, chipId={}, expectedTargetMm={}, actualTargetMm={}",
                        sliderLamp.getChipId(), snapshot.targetPositionMm(), reqVO.getTargetMm());
                return;
            }
            sliderMotionStateService.completeMotion(
                    sliderLamp.getChipId(),
                    sliderLamp.getStoreId(),
                    reqVO.getTargetMm()
            );
        } catch (RuntimeException e) {
            log.warn("slider status reconciliation failed, chipId={}, targetMm={}",
                    reqVO.getChipId(), reqVO.getTargetMm(), e);
        }
    }

    private TimedSliderMotion planSliderMotion(
            DeviceCamRoiConfigVO config,
            int targetIndex,
            DeviceDO sliderLamp,
            double targetPositionMm) {
        SliderMotionStateService.SliderStateSnapshot snapshot = sliderMotionStateService.getSnapshot(
                sliderLamp.getChipId(),
                sliderLamp.getStoreId()
        );
        String confirmedSpeedMode = sliderMotionStateService.requireConfirmedSpeedMode(
                sliderLamp.getChipId(), sliderLamp.getStoreId());
        SliderCalibration calibration = resolveSliderCalibration(config, targetIndex, confirmedSpeedMode);
        long delayMs = SliderMotionEstimator.estimateDelayMs(
                snapshot.currentPositionMm(),
                targetPositionMm,
                calibration.distanceMm(),
                calibration.timeSeconds()
        );
        return new TimedSliderMotion(
                snapshot.currentPositionMm(),
                targetPositionMm,
                confirmedSpeedMode,
                delayMs
        );
    }

    private double resolveStandbySliderPreset(DeviceCamRoiConfigVO config, List<BatchCaptureTarget> targets) {
        BatchCaptureTarget lampTwo = targets.stream().filter(target -> target.targetIndex() == 2)
                .findFirst().orElseThrow(() -> new ServiceException("区域 2 滑轨预设缺失"));
        BatchCaptureTarget farthest = targets.stream()
                .max(Comparator.comparingDouble(target -> Math.abs(
                        target.sliderTargetMm() - lampTwo.sliderTargetMm())))
                .orElseThrow(() -> new ServiceException("拍摄目标滑轨预设缺失"));
        if (Math.abs(lampTwo.sliderTargetMm() - farthest.sliderTargetMm()) < 1D) {
            throw new ServiceException("区域 2 与最远灯的滑轨预设过近，无法自动计算安全待机位");
        }
        return Math.round((lampTwo.sliderTargetMm() + farthest.sliderTargetMm()) / 2D);
    }

    private double resolveSingleReturnSliderPreset(DeviceCamRoiConfigVO config, int targetIndex) {
        double targetOneMm = resolveSliderPreset(config, 1);
        double targetTwoMm = resolveSliderPreset(config, 2);
        double targetThreeMm = resolveSliderPreset(config, 3);
        double targetMm;
        if (targetIndex == 1) {
            targetMm = midpoint(targetOneMm, targetTwoMm);
        } else if (targetIndex == 3) {
            targetMm = midpoint(targetTwoMm, targetThreeMm);
        } else {
            double leftMidpoint = midpoint(targetOneMm, targetTwoMm);
            double rightMidpoint = midpoint(targetTwoMm, targetThreeMm);
            targetMm = Math.abs(leftMidpoint - targetTwoMm) >= Math.abs(rightMidpoint - targetTwoMm)
                    ? leftMidpoint : rightMidpoint;
        }
        double capturedTargetMm = resolveSliderPreset(config, targetIndex);
        if (Math.abs(targetMm - capturedTargetMm) < 1D) {
            throw new ServiceException("相邻灯滑轨预设过近，无法自动计算单点拍照安全撤离位置");
        }
        return Math.round(targetMm);
    }

    private double midpoint(double firstMm, double secondMm) {
        return (firstMm + secondMm) / 2D;
    }

    private void beginSliderMotion(DeviceDO sliderLamp, TimedSliderMotion motionPlan) {
        LocalDateTime startedAt = LocalDateTime.now();
        long travelMs = Math.max(1L, motionPlan.delayMs() - SliderMotionEstimator.SAFETY_MARGIN_MS);
        sliderMotionStateService.beginMotion(
                sliderLamp.getChipId(),
                sliderLamp.getStoreId(),
                motionPlan.currentPositionMm(),
                motionPlan.targetPositionMm(),
                motionPlan.speedMode(),
                startedAt,
                startedAt.plusNanos(TimeUnit.MILLISECONDS.toNanos(travelMs))
        );
    }

    private void validateBatchMotionCalibration(
            DeviceCamRoiConfigVO config,
            DeviceDO sliderLamp,
            List<BatchCaptureTarget> targets) {
        SliderMotionStateService.SliderStateSnapshot snapshot = sliderMotionStateService.getSnapshot(
                sliderLamp.getChipId(),
                sliderLamp.getStoreId()
        );
        String confirmedSpeedMode = sliderMotionStateService.requireConfirmedSpeedMode(
                sliderLamp.getChipId(), sliderLamp.getStoreId());
        for (BatchCaptureTarget target : targets) {
            SliderCalibration calibration = resolveSliderCalibration(
                    config,
                    target.targetIndex(),
                    confirmedSpeedMode
            );
            SliderMotionEstimator.estimateDelayMs(
                    snapshot.currentPositionMm(),
                    target.sliderTargetMm(),
                    calibration.distanceMm(),
                    calibration.timeSeconds()
            );
        }
        BatchCaptureTarget finalTarget = targets.get(targets.size() - 1);
        planSliderMotion(
                config,
                finalTarget.targetIndex(),
                sliderLamp,
                resolveStandbySliderPreset(config, targets)
        );
    }

    private List<BatchCaptureTarget> buildBatchCaptureTargets(DeviceCamRoiConfigVO config, Long storeId) {
        List<DeviceCamRoiItemVO> rois = config.getRois() == null ? List.of() : config.getRois();
        java.util.ArrayList<BatchCaptureTarget> targets = new java.util.ArrayList<>();
        for (int targetIndex = 1; targetIndex <= BATCH_TARGET_COUNT; targetIndex++) {
            int currentIndex = targetIndex;
            DeviceCamRoiItemVO roi = rois.stream()
                    .filter(item -> item.getTargetIndex() != null && item.getTargetIndex() == currentIndex)
                    .findFirst()
                    .orElseThrow(() -> new ServiceException("区域 " + currentIndex + " 未配置，无法批量拍摄"));
            if (!notBlank(roi.getTargetChipId())) {
                throw new ServiceException("区域 " + currentIndex + " 目标灯缺失，无法批量拍摄");
            }
            DeviceDO target = requireLampLike(roi.getTargetChipId());
            if (target.getStoreId() == null || !target.getStoreId().equals(storeId)) {
                throw new ServiceException("区域 " + currentIndex + " 目标灯不属于当前门店");
            }
            targets.add(new BatchCaptureTarget(
                    currentIndex,
                    target.getChipId(),
                    resolveSliderPreset(config, currentIndex)
            ));
        }
        targets.sort(Comparator
                .comparingDouble(BatchCaptureTarget::sliderTargetMm)
                .thenComparingInt(BatchCaptureTarget::targetIndex));
        return List.copyOf(targets);
    }

    private void startBatchTarget(CaptureBatchContext context, int index) {
        DeviceCamCaptureTaskRespVO task;
        BatchCaptureTarget target;
        synchronized (context) {
            if (!"running".equals(context.batch().getStatus())
                    || index < 0
                    || index >= context.targets().size()) {
                return;
            }
            context.setCurrentIndex(index);
            task = context.batch().getTasks().get(index);
            target = context.targets().get(index);
            task.setStatus("waiting_motion");
            task.setMessage("waiting for estimated slider motion time");
        }

        DeviceDO sliderLamp = requireLampLike(context.sliderLampChipId());
        TimedSliderMotion motionPlan;
        try {
            motionPlan = planSliderMotion(
                    context.config(),
                    target.targetIndex(),
                    sliderLamp,
                    target.sliderTargetMm()
            );
        } catch (RuntimeException e) {
            log.warn("batch slider motion planning failed, batchId={}, taskId={}",
                    context.batch().getBatchId(), task.getTaskId(), e);
            failBatchPhysicalTask(task, "motion_config_invalid", e.getMessage());
            return;
        }

        String uploadToken = captureUploadTokens.get(task.getTaskId());
        DeviceCamRoiItemVO captureRoi = requireCaptureRoi(context.config(), target.targetIndex());
        PendingCaptureMotion pending = new PendingCaptureMotion(
                task.getCamChipId(),
                context.sliderLampChipId(),
                context.captureControllerChipId(),
                captureRoi.getGarmentCapturePan(),
                captureRoi.getGarmentCaptureTilt(),
                target.targetChipId(),
                target.targetIndex(),
                target.sliderTargetMm(),
                uploadToken,
                context.storeId()
        );
        pendingCaptureMotions.put(task.getTaskId(), pending);

        CollisionGuardSession guardSession;
        try {
            guardSession = prepareCollisionGuards(context.config(), motionPlan, task.getTaskId());
            retainCollisionGuards(task.getTaskId(), guardSession);
        } catch (RuntimeException e) {
            pendingCaptureMotions.remove(task.getTaskId(), pending);
            failBatchPhysicalTask(task, "collision_guard_failed", e.getMessage());
            return;
        }
        webSocketPushService.pushCamCaptureTask(task, context.storeId());
        Runnable dispatch = () -> {
            try {
                sendSliderMotionCommand(context.sliderLampChipId(), target.sliderTargetMm(),
                        "camera_capture", task.getTaskId());
                beginSliderMotion(sliderLamp, motionPlan);
                scheduleTimedCapture(task.getTaskId(), motionPlan.delayMs());
            } catch (RuntimeException e) {
                releaseRetainedCollisionGuards(task.getTaskId());
                pendingCaptureMotions.remove(task.getTaskId(), pending);
                failBatchPhysicalTask(task, "motion_command_failed", e.getMessage());
            }
        };
        if (guardSession.parkDelayMs() > 0L) {
            captureTimeoutExecutor.schedule(dispatch, guardSession.parkDelayMs(), TimeUnit.MILLISECONDS);
        } else {
            dispatch.run();
        }
    }

    private void advanceCaptureBatch(DeviceCamCaptureTaskRespVO completedTask) {
        String batchId = captureBatchByTask.get(completedTask.getTaskId());
        CaptureBatchContext context = batchId == null ? null : batchContexts.get(batchId);
        if (context == null) {
            return;
        }

        int nextIndex;
        synchronized (context) {
            int currentIndex = context.currentIndex();
            if (currentIndex < 0
                    || currentIndex >= context.batch().getTasks().size()
                    || !context.batch().getTasks().get(currentIndex).getTaskId().equals(completedTask.getTaskId())) {
                return;
            }
            nextIndex = currentIndex + 1;
            context.setCurrentIndex(nextIndex);
        }
        if (nextIndex < context.targets().size()) {
            startBatchTarget(context, nextIndex);
        } else {
            startBatchReturn(context);
        }
    }

    private void failBatchPhysicalTask(DeviceCamCaptureTaskRespVO task, String status, String message) {
        task.setStatus(status);
        task.setMessage(message);
        captureUploadTokens.remove(task.getTaskId());
        pendingCaptureMotions.remove(task.getTaskId());
        String batchId = captureBatchByTask.get(task.getTaskId());
        CaptureBatchContext context = batchId == null ? null : batchContexts.get(batchId);
        if (context != null) {
            webSocketPushService.pushCamCaptureResult(task, context.storeId());
        }
        scheduleTaskCleanup(task.getTaskId());
        refreshAutomaticDetectionBatch(batchId);
        advanceCaptureBatch(task);
    }

    private void startBatchReturn(CaptureBatchContext context) {
        DeviceCamCaptureBatchRespVO batch = context.batch();
        synchronized (context) {
            if (!"running".equals(batch.getStatus())) {
                return;
            }
            batch.setStatus("returning_standby");
            batch.setMessage("all photos received; returning to standby position");
        }

        PendingBatchReturn pending = new PendingBatchReturn(
                context.sliderLampChipId(),
                context.standbySliderMm(),
                context.storeId()
        );
        pendingBatchReturns.put(batch.getBatchId(), pending);
        webSocketPushService.pushCamStatus(Map.of(
                "camChipId", batch.getCamChipId(),
                "workStatus", "returning_standby",
                "batchId", batch.getBatchId(),
                "message", "三张图片已收到，滑轨返回安全待机位"
        ), context.storeId());

        DeviceDO sliderLamp = requireLampLike(context.sliderLampChipId());
        TimedSliderMotion motionPlan;
        try {
            DeviceCamCaptureTaskRespVO finalTask = context.batch().getTasks()
                    .get(context.batch().getTasks().size() - 1);
            motionPlan = planSliderMotion(
                    context.config(),
                    finalTask.getTargetIndex(),
                    sliderLamp,
                    context.standbySliderMm()
            );
        } catch (RuntimeException e) {
            log.warn("batch return motion planning failed, batchId={}", batch.getBatchId(), e);
            pendingBatchReturns.remove(batch.getBatchId(), pending);
            finishCaptureBatch(context, "return_failed", "failed to plan return to standby position", "error");
            return;
        }
        CollisionGuardSession guardSession;
        try {
            guardSession = prepareCollisionGuards(context.config(), motionPlan, batch.getBatchId());
            retainCollisionGuards(batch.getBatchId(), guardSession);
        } catch (RuntimeException e) {
            pendingBatchReturns.remove(batch.getBatchId(), pending);
            finishCaptureBatch(context, "return_failed", e.getMessage(), "error");
            return;
        }
        Runnable dispatch = () -> {
            try {
                sendSliderMotionCommand(context.sliderLampChipId(), context.standbySliderMm(),
                        "camera_batch_return", batch.getBatchId());
                beginSliderMotion(sliderLamp, motionPlan);
                scheduleTimedBatchReturn(batch.getBatchId(), motionPlan.delayMs());
            } catch (RuntimeException e) {
                pendingBatchReturns.remove(batch.getBatchId(), pending);
                finishCaptureBatch(context, "return_failed", e.getMessage(), "error");
            }
        };
        if (guardSession.parkDelayMs() > 0L) {
            captureTimeoutExecutor.schedule(dispatch, guardSession.parkDelayMs(), TimeUnit.MILLISECONDS);
        } else {
            dispatch.run();
        }
    }

    private void finishCaptureBatch(
            CaptureBatchContext context,
            String status,
            String message,
            String camWorkStatus) {
        DeviceCamCaptureBatchRespVO batch = context.batch();
        synchronized (context) {
            if ("completed".equals(batch.getStatus()) || "return_failed".equals(batch.getStatus())) {
                return;
            }
            batch.setStatus(status);
            batch.setMessage(message);
        }
        pendingBatchReturns.remove(batch.getBatchId());
        releaseBatchCollisionGuards(context);
        releaseCaptureSlots(batch.getBatchId(), batch.getCamChipId());
        webSocketPushService.pushCamStatus(Map.of(
                "camChipId", batch.getCamChipId(),
                "workStatus", camWorkStatus,
                "batchId", batch.getBatchId(),
                "message", message
        ), context.storeId());
        refreshAutomaticDetectionBatch(batch.getBatchId());
        scheduleBatchCleanup(batch.getBatchId());
    }

    @Override
    public DeviceCamCaptureTaskRespVO uploadCapturePhoto(String taskId, MultipartFile file) {
        DeviceCamCaptureTaskRespVO task = taskCache.get(taskId);
        if (task == null) {
            throw new ServiceException("拍摄任务不存在或已过期");
        }
        DeviceDO cam = requireCam(task.getCamChipId());
        String originalFilename;
        String contentType;
        Path storedPath;
        DeviceCamCaptureTaskRespVO receivedSnapshot;
        synchronized (task) {
            if (notBlank(task.getImageName()) && isPhotoAlreadyReceived(task.getStatus())) {
                return copyCaptureTask(task);
            }
            task.setStatus("uploading");
            task.setMessage("uploading capture photo");
            webSocketPushService.pushCamCaptureResult(task, cam.getStoreId());
            originalFilename = file == null ? null : file.getOriginalFilename();
            contentType = file == null ? null : file.getContentType();
            try {
                String imageName = saveUpload(file, "capture", taskId);
                task.setImageName(imageName);
                task.setPhotoUrl("/admin/device/cam/upload/" + imageName);
                storedPath = CAM_UPLOAD_DIR.resolve(imageName).normalize();
            } catch (RuntimeException e) {
                task.setStatus("upload_failed");
                task.setMessage("capture photo upload failed, retry allowed");
                if (!notBlank(task.getBatchId())) {
                    releaseCaptureSlots(task);
                    scheduleTaskCleanup(taskId);
                }
                webSocketPushService.pushCamCaptureResult(task, cam.getStoreId());
                refreshAutomaticDetectionBatch(task.getBatchId());
                throw e;
            }
            task.setStatus("image_received");
            task.setMessage("capture photo received");
            receivedSnapshot = copyCaptureTask(task);
        }

        log.info("pushCamCaptureResult taskId={} status={} imageName={} photoUrl={} storeId={}",
                taskId, task.getStatus(), task.getImageName(), task.getPhotoUrl(), cam.getStoreId());
        webSocketPushService.pushCamCaptureResult(task, cam.getStoreId());

        if (notBlank(task.getBatchId())) {
            advanceCaptureBatch(task);
        } else {
            startSingleCaptureReturn(task, cam);
        }
        enqueueCaptureAi(task, cam.getStoreId(), storedPath, originalFilename, contentType);
        return receivedSnapshot;
    }

    private void startSingleCaptureReturn(DeviceCamCaptureTaskRespVO task, DeviceDO cam) {
        String taskId = task.getTaskId();
        PendingSingleReturn pending;
        DeviceDO sliderLamp;
        DeviceCamRoiConfigVO config;
        TimedSliderMotion motionPlan;
        CollisionGuardSession guardSession;
        try {
            config = readRoiConfig(cam.getChipId());
            String sliderLampChipId = captureSliderLampByTask.get(taskId);
            if (!notBlank(sliderLampChipId)) {
                throw new ServiceException("单点拍照滑轨占用信息已丢失");
            }
            sliderLamp = requireLampLike(sliderLampChipId);
            double standbySliderMm = resolveSingleReturnSliderPreset(config, task.getTargetIndex());
            motionPlan = planSliderMotion(config, task.getTargetIndex(), sliderLamp, standbySliderMm);
            pending = new PendingSingleReturn(
                    cam.getChipId(), sliderLamp.getChipId(), standbySliderMm, cam.getStoreId());
            pendingSingleReturns.put(taskId, pending);
            guardSession = prepareCollisionGuards(config, motionPlan, taskId);
            retainCollisionGuards(taskId, guardSession);
        } catch (RuntimeException e) {
            failSingleCaptureReturn(task, cam, null, e.getMessage());
            return;
        }

        webSocketPushService.pushCamStatus(Map.of(
                "camChipId", cam.getChipId(),
                "workStatus", "returning_standby",
                "taskId", taskId,
                "targetIndex", task.getTargetIndex(),
                "message", "单点拍照完成，滑轨返回安全待机位"
        ), cam.getStoreId());

        Runnable dispatch = () -> {
            try {
                sendSliderMotionCommand(pending.sliderLampChipId(), pending.targetMm(),
                        "camera_single_return", taskId);
                beginSliderMotion(sliderLamp, motionPlan);
                scheduleTimedSingleReturn(taskId, motionPlan.delayMs());
            } catch (RuntimeException e) {
                failSingleCaptureReturn(task, cam, pending, e.getMessage());
            }
        };
        if (guardSession.parkDelayMs() > 0L) {
            captureTimeoutExecutor.schedule(dispatch, guardSession.parkDelayMs(), TimeUnit.MILLISECONDS);
        } else {
            dispatch.run();
        }
    }

    private void failSingleCaptureReturn(
            DeviceCamCaptureTaskRespVO task, DeviceDO cam, PendingSingleReturn pending, String reason) {
        if (pending == null) {
            pendingSingleReturns.remove(task.getTaskId());
        } else {
            pendingSingleReturns.remove(task.getTaskId(), pending);
        }
        releaseRetainedCollisionGuards(task.getTaskId());
        releaseCaptureSlots(task);
        String detail = notBlank(reason) ? reason : "未知错误";
        try {
            webSocketPushService.pushCamStatus(Map.of(
                    "camChipId", cam.getChipId(),
                    "workStatus", "error",
                    "taskId", task.getTaskId(),
                    "targetIndex", task.getTargetIndex(),
                    "message", "照片已保存，但滑轨返回安全待机位失败：" + detail
            ), cam.getStoreId());
        } catch (RuntimeException e) {
            log.warn("single capture return failure push failed, taskId={}", task.getTaskId(), e);
        }
    }

    private void enqueueCaptureAi(
            DeviceCamCaptureTaskRespVO task,
            Long storeId,
            Path storedPath,
            String originalFilename,
            String contentType) {
        try {
            captureAiExecutor.execute(() -> processCaptureAi(
                    task,
                    storeId,
                    storedPath,
                    originalFilename,
                    contentType
            ));
        } catch (RuntimeException e) {
            log.warn("capture AI queue rejected, taskId={}", task.getTaskId(), e);
            synchronized (task) {
                task.setStatus("photo_saved_ai_failed");
                task.setMessage("photo saved but AI queue failed");
            }
            webSocketPushService.pushCamCaptureResult(task, storeId);
            refreshAutomaticDetectionBatch(task.getBatchId());
            scheduleTaskCleanup(task.getTaskId());
        }
    }

    private void processCaptureAi(
            DeviceCamCaptureTaskRespVO task,
            Long storeId,
            Path storedPath,
            String originalFilename,
            String contentType) {
        synchronized (task) {
            if (!"image_received".equals(task.getStatus())) {
                return;
            }
            task.setStatus("ai_processing");
            task.setMessage("AI processing");
        }
        webSocketPushService.pushCamCaptureResult(task, storeId);
        try {
            aiService.fabricRecognize(
                    task.getTargetChipId(),
                    new StoredImageMultipartFile(storedPath, originalFilename, contentType)
            );
            synchronized (task) {
                task.setStatus("ai_done");
                task.setMessage("capture photo saved and AI finished");
            }
        } catch (Exception e) {
            synchronized (task) {
                task.setStatus("photo_saved_ai_failed");
                task.setMessage("photo saved but AI failed");
            }
            log.warn("cam capture photo saved but AI failed, taskId={}, target={}",
                    task.getTaskId(), task.getTargetChipId(), e);
        }
        log.info("pushCamCaptureResult taskId={} status={} imageName={} photoUrl={} storeId={}",
                task.getTaskId(), task.getStatus(), task.getImageName(), task.getPhotoUrl(), storeId);
        webSocketPushService.pushCamCaptureResult(task, storeId);
        refreshAutomaticDetectionBatch(task.getBatchId());
        scheduleTaskCleanup(task.getTaskId());
    }

    private void refreshAutomaticDetectionBatch(String batchId) {
        if (!notBlank(batchId)) {
            return;
        }
        Map.Entry<Long, String> automaticEntry = automaticDetectionBatchByStore.entrySet().stream()
                .filter(entry -> batchId.equals(entry.getValue()))
                .findFirst()
                .orElse(null);
        if (automaticEntry == null) {
            return;
        }
        DeviceCamCaptureBatchRespVO batch = batchCache.get(batchId);
        if (batch == null) {
            return;
        }

        Set<String> failedTaskStatuses = Set.of(
                "motion_config_invalid",
                "motion_command_failed",
                "motion_state_failed",
                "timeout",
                "upload_failed",
                "photo_saved_ai_failed",
                "camera_offline",
                "camera_command_failed",
                "capture_controller_offline",
                "capture_controller_command_failed"
        );
        boolean failed = Set.of("return_failed", "error", "cancelled")
                .contains(defaultStatus(batch.getStatus(), ""))
                || batch.getTasks().stream()
                        .anyMatch(task -> failedTaskStatuses.contains(defaultStatus(task.getStatus(), "")));
        if (failed) {
            if (failedAutomaticDetectionBatches.add(batchId)) {
                updateGarmentDetectionStatus(
                        automaticEntry.getKey(),
                        "not_detected",
                        "全区域检测未全部完成"
                );
            }
            if (Set.of("completed", "return_failed", "error", "cancelled")
                    .contains(defaultStatus(batch.getStatus(), ""))) {
                automaticDetectionBatchByStore.remove(automaticEntry.getKey(), batchId);
                failedAutomaticDetectionBatches.remove(batchId);
            }
            return;
        }

        boolean allAiDone = !batch.getTasks().isEmpty()
                && batch.getTasks().stream().allMatch(task -> "ai_done".equals(task.getStatus()));
        if ("completed".equals(batch.getStatus()) && allAiDone
                && automaticDetectionBatchByStore.remove(automaticEntry.getKey(), batchId)) {
            failedAutomaticDetectionBatches.remove(batchId);
            updateGarmentDetectionStatus(
                    automaticEntry.getKey(),
                    "detected",
                    "全区域服装检测完成"
            );
        }
    }

    @Override
    public DeviceCamCaptureTaskRespVO uploadCapturePhotoByDevice(String taskId, String token, MultipartFile file) {
        validateCaptureUploadToken(taskId, token);
        return uploadCapturePhoto(taskId, file);
    }

    private void validateCaptureUploadToken(String taskId, String token) {
        String expected = captureUploadTokens.get(taskId);
        if (!notBlank(expected) || !notBlank(token) || !expected.equals(token.trim())) {
            throw new ServiceException("cam capture upload token invalid");
        }
    }

    @Override
    public void uploadFlowPhoto(String camChipId, Integer personCount, Double confidence, String detectTime, MultipartFile file) {
        DeviceDO cam = requireCam(camChipId);
        if (personCount == null) {
            aiService.personDetect(cam.getChipId(), file);
            return;
        }
        String imageName = saveUpload(file, "flow", cam.getChipId());
        saveFlowRecordIfNeeded(cam, personCount, confidence, detectTime, imageName);
    }

    private void saveFlowRecordIfNeeded(DeviceDO cam, Integer personCount, Double confidence, String detectTime, String imageName) {
        if (personCount == null && !notBlank(imageName)) {
            return;
        }
        PersonFlowRecordDO record = new PersonFlowRecordDO();
        record.setStoreId(cam.getStoreId());
        record.setChipId(cam.getChipId());
        record.setSource("CAM");
        record.setPersonCount(personCount == null ? 0 : Math.max(0, personCount));
        record.setConfidence(confidence);
        record.setProcessingTime(0D);
        record.setDetectTime(parseTime(detectTime));
        record.setImageName(imageName);
        personFlowRecordService.saveRecord(record);
        Map<String, Object> payload = new HashMap<>();
        payload.put("camChipId", cam.getChipId());
        payload.put("workStatus", "monitoring");
        payload.put("personCount", record.getPersonCount());
        payload.put("imageName", imageName);
        payload.put("detectTime", record.getDetectTime());
        webSocketPushService.pushCamStatus(payload, cam.getStoreId());
    }

    @Override
    public void uploadFlowPhotoByDevice(
            String camChipId,
            String captureControllerChipId,
            String token,
            Integer personCount,
            Double confidence,
            String detectTime,
            MultipartFile file) {
        String tokenOwnerChipId = notBlank(captureControllerChipId)
                ? captureControllerChipId.trim()
                : camChipId;
        if (!deviceSessionManager.validateUploadToken(tokenOwnerChipId, token)) {
            throw new ServiceException("cam flow upload token invalid");
        }
        if (notBlank(captureControllerChipId)) {
            requireBoundCaptureController(camChipId, captureControllerChipId);
        }
        uploadFlowPhoto(camChipId, personCount, confidence, detectTime, file);
    }

    private void requireBoundCaptureController(String camChipId, String captureControllerChipId) {
        DeviceDO cam = requireCam(camChipId);
        DeviceDO captureController = requireCaptureController(captureControllerChipId);
        requireSameStore(cam, captureController);
        DeviceCamRoiConfigVO config = readRoiConfig(cam.getChipId());
        if (!sameChipId(config.getCaptureControllerChipId(), captureController.getChipId())) {
            throw new ServiceException("拍照控制器未绑定到指定 cam");
        }
    }

    @Override
    public Resource loadUploadImage(String imageName) {
        if (!notBlank(imageName)) {
            throw new ServiceException("cam upload image name required");
        }
        Path path = CAM_UPLOAD_DIR.resolve(imageName).normalize();
        if (!path.startsWith(CAM_UPLOAD_DIR) || !Files.isRegularFile(path)) {
            throw new ServiceException("cam upload image not found");
        }
        return new FileSystemResource(path);
    }

    private void scheduleTimedCapture(String taskId, long delayMs) {
        captureTimeoutExecutor.schedule(() -> triggerTimedCapture(taskId), delayMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleCaptureTimeout(String taskId) {
        captureTimeoutExecutor.schedule(() -> timeoutCaptureTask(taskId), CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void scheduleTimedBatchReturn(String batchId, long delayMs) {
        captureTimeoutExecutor.schedule(() -> finishTimedBatchReturn(batchId), delayMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleTimedSingleReturn(String taskId, long delayMs) {
        captureTimeoutExecutor.schedule(() -> finishTimedSingleReturn(taskId), delayMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleTaskCleanup(String taskId) {
        // 任务终态后延迟 5 分钟清理，给前端足够时间查询状态
        captureTimeoutExecutor.schedule(() -> {
            DeviceCamCaptureTaskRespVO task = taskCache.remove(taskId);
            captureUploadTokens.remove(taskId);
            pendingCaptureMotions.remove(taskId);
            pendingSingleReturns.remove(taskId);
            releaseRetainedCollisionGuards(taskId);
            captureBatchByTask.remove(taskId);
            if (task != null) {
                releaseCaptureSlots(task);
            }
        }, 5, TimeUnit.MINUTES);
    }

    private void scheduleBatchCleanup(String batchId) {
        captureTimeoutExecutor.schedule(() -> {
            batchCache.remove(batchId);
            CaptureBatchContext context = batchContexts.remove(batchId);
            pendingBatchReturns.remove(batchId);
            if (context != null) {
                releaseBatchCollisionGuards(context);
            } else {
                releaseRetainedCollisionGuards(batchId);
            }
        }, 5, TimeUnit.MINUTES);
    }

    private void triggerTimedCapture(String taskId) {
        DeviceCamCaptureTaskRespVO task = taskCache.get(taskId);
        PendingCaptureMotion pending = pendingCaptureMotions.remove(taskId);
        if (task == null || pending == null || !"waiting_motion".equals(task.getStatus())) {
            return;
        }
        try {
            sliderMotionStateService.completeMotion(
                    pending.sliderLampChipId(),
                    pending.storeId(),
                    pending.sliderTargetMm()
            );
        } catch (RuntimeException e) {
            log.warn("slider state completion failed, taskId={}", taskId, e);
        }
        if (!deviceSessionManager.isOnline(pending.captureControllerChipId())) {
            failCaptureTask(task, "capture_controller_offline", "estimated slider time elapsed but capture controller is offline", pending.storeId());
            return;
        }

        ObjectNode capture = objectMapper.createObjectNode();
        capture.put("type", "cameraCapture");
        capture.put("taskId", taskId);
        capture.put("camChipId", pending.camChipId());
        capture.put("captureControllerChipId", pending.captureControllerChipId());
        capture.put("targetChipId", pending.targetChipId());
        capture.put("targetIndex", pending.targetIndex());
        capture.put("captureKind", "garment");
        capture.put("motionReady", true);
        capture.put("uploadUrl", "/device/cam/capture-task/" + taskId + "/photo");
        capture.put("uploadToken", pending.uploadToken());
        ObjectNode capturePreset = capture.putObject("capturePreset");
        capturePreset.put("pan", pending.capturePan());
        capturePreset.put("tilt", pending.captureTilt());

        log.info(
                "sending camera capture command, taskId={}, batchId={}, camChipId={}, "
                        + "captureControllerChipId={}, targetChipId={}, targetIndex={}, "
                        + "captureKind=garment, pan={}, tilt={}",
                taskId,
                task.getBatchId(),
                pending.camChipId(),
                pending.captureControllerChipId(),
                pending.targetChipId(),
                pending.targetIndex(),
                pending.capturePan(),
                pending.captureTilt()
        );
        boolean captureSent;
        try {
            captureSent = deviceSessionManager.sendToDevice(pending.captureControllerChipId(), capture.toString());
        } catch (RuntimeException e) {
            log.warn(
                    "camera capture command send threw exception, taskId={}, captureControllerChipId={}, "
                            + "targetIndex={}, pan={}, tilt={}",
                    taskId,
                    pending.captureControllerChipId(),
                    pending.targetIndex(),
                    pending.capturePan(),
                    pending.captureTilt(),
                    e
            );
            failCaptureTask(task, "capture_controller_command_failed", "capture controller command send failed", pending.storeId());
            return;
        }
        if (!captureSent) {
            log.warn(
                    "camera capture command not delivered, taskId={}, captureControllerChipId={}, "
                            + "targetIndex={}, pan={}, tilt={}",
                    taskId,
                    pending.captureControllerChipId(),
                    pending.targetIndex(),
                    pending.capturePan(),
                    pending.captureTilt()
            );
            failCaptureTask(task, "capture_controller_command_failed", "capture controller command send failed", pending.storeId());
            return;
        }
        log.info(
                "camera capture command sent, taskId={}, captureControllerChipId={}, "
                        + "targetIndex={}, pan={}, tilt={}",
                taskId,
                pending.captureControllerChipId(),
                pending.targetIndex(),
                pending.capturePan(),
                pending.captureTilt()
        );

        task.setStatus("capturing");
        task.setMessage("estimated slider time elapsed; capture command sent");
        webSocketPushService.pushCamCaptureTask(task, pending.storeId());
        scheduleCaptureTimeout(taskId);
    }

    private void timeoutCaptureTask(String taskId) {
        DeviceCamCaptureTaskRespVO task = taskCache.get(taskId);
        if (task == null || !Set.of("capturing", "uploading", "upload_failed").contains(task.getStatus())) {
            return;
        }
        if ("upload_failed".equals(task.getStatus()) && !notBlank(task.getBatchId())) {
            return;
        }
        if (notBlank(task.getBatchId())) {
            failBatchPhysicalTask(task, "timeout", "capture photo upload timeout");
            return;
        }
        task.setStatus("timeout");
        task.setMessage("capture photo upload timeout");
        releaseRetainedCollisionGuards(taskId);
        releaseCaptureSlots(task);
        scheduleTaskCleanup(taskId);
        try {
            DeviceDO cam = requireCam(task.getCamChipId());
            webSocketPushService.pushCamCaptureResult(task, cam.getStoreId());
            webSocketPushService.pushCamStatus(Map.of(
                    "camChipId", cam.getChipId(),
                    "workStatus", "error",
                    "targetIndex", task.getTargetIndex(),
                    "targetChipId", task.getTargetChipId(),
                    "message", task.getMessage()
            ), cam.getStoreId());
        } catch (Exception e) {
            log.warn("cam capture task timeout push failed, taskId={}", taskId, e);
        }
    }

    private void finishTimedBatchReturn(String batchId) {
        PendingBatchReturn pending = pendingBatchReturns.remove(batchId);
        CaptureBatchContext context = batchContexts.get(batchId);
        if (pending == null || context == null || !"returning_standby".equals(context.batch().getStatus())) {
            return;
        }
        try {
            sliderMotionStateService.completeMotion(
                    pending.sliderLampChipId(),
                    pending.storeId(),
                    pending.targetMm()
            );
        } catch (RuntimeException e) {
            log.warn("batch return slider state completion failed, batchId={}", batchId, e);
        }
        finishCaptureBatch(context, "completed", "batch capture completed at standby position", "batch_complete");
    }

    private void finishTimedSingleReturn(String taskId) {
        PendingSingleReturn pending = pendingSingleReturns.remove(taskId);
        if (pending == null) {
            return;
        }
        try {
            sliderMotionStateService.completeMotion(
                    pending.sliderLampChipId(), pending.storeId(), pending.targetMm());
        } catch (RuntimeException e) {
            log.warn("single capture return slider state completion failed, taskId={}", taskId, e);
        }
        releaseRetainedCollisionGuards(taskId);
        releaseCaptureSlots(taskId, pending.camChipId());
        try {
            webSocketPushService.pushCamStatus(Map.of(
                    "camChipId", pending.camChipId(),
                    "workStatus", "capture_complete",
                    "taskId", taskId,
                    "message", "单点拍照完成，滑轨已回到安全待机位"
            ), pending.storeId());
        } catch (RuntimeException e) {
            log.warn("single capture return completion push failed, taskId={}", taskId, e);
        }
    }

    private void claimCaptureSlot(Map<String, String> slots, String chipId, String taskId, String message) {
        while (true) {
            String existingTaskId = slots.putIfAbsent(chipId, taskId);
            if (existingTaskId == null || existingTaskId.equals(taskId)) {
                return;
            }
            if (!isCaptureOperationActive(existingTaskId)) {
                slots.remove(chipId, existingTaskId);
                continue;
            }
            throw new ServiceException(message);
        }
    }

    private boolean isCaptureOperationActive(String operationId) {
        if (pendingSingleReturns.containsKey(operationId)) {
            return true;
        }
        DeviceCamCaptureBatchRespVO batch = batchCache.get(operationId);
        if (batch != null) {
            return "running".equals(batch.getStatus()) || "returning_standby".equals(batch.getStatus());
        }
        DeviceCamCaptureTaskRespVO task = taskCache.get(operationId);
        if (task == null) {
            return false;
        }
        return Set.of("waiting_motion", "capturing", "uploading", "upload_failed")
                .contains(task.getStatus());
    }

    private void discardCaptureTask(DeviceCamCaptureTaskRespVO task) {
        taskCache.remove(task.getTaskId());
        captureUploadTokens.remove(task.getTaskId());
        pendingCaptureMotions.remove(task.getTaskId());
        pendingSingleReturns.remove(task.getTaskId());
        releaseRetainedCollisionGuards(task.getTaskId());
        releaseCaptureSlots(task);
    }

    private void discardCaptureBatch(String batchId) {
        DeviceCamCaptureBatchRespVO batch = batchCache.remove(batchId);
        CaptureBatchContext context = batchContexts.remove(batchId);
        pendingBatchReturns.remove(batchId);
        if (batch != null) {
            for (DeviceCamCaptureTaskRespVO task : batch.getTasks()) {
                releaseRetainedCollisionGuards(task.getTaskId());
                taskCache.remove(task.getTaskId());
                captureUploadTokens.remove(task.getTaskId());
                pendingCaptureMotions.remove(task.getTaskId());
                captureBatchByTask.remove(task.getTaskId());
            }
        } else if (context != null) {
            for (DeviceCamCaptureTaskRespVO task : context.batch().getTasks()) {
                releaseRetainedCollisionGuards(task.getTaskId());
            }
        }
        releaseRetainedCollisionGuards(batchId);
        String camChipId = batch != null
                ? batch.getCamChipId()
                : (context == null ? null : context.batch().getCamChipId());
        if (notBlank(camChipId)) {
            releaseCaptureSlots(batchId, camChipId);
        }
    }

    private boolean isPhotoAlreadyReceived(String status) {
        return Set.of("image_received", "ai_processing", "ai_done", "photo_saved_ai_failed")
                .contains(defaultStatus(status, ""));
    }

    private DeviceCamCaptureTaskRespVO copyCaptureTask(DeviceCamCaptureTaskRespVO source) {
        DeviceCamCaptureTaskRespVO copy = new DeviceCamCaptureTaskRespVO();
        copy.setTaskId(source.getTaskId());
        copy.setBatchId(source.getBatchId());
        copy.setSequence(source.getSequence());
        copy.setCamChipId(source.getCamChipId());
        copy.setTargetChipId(source.getTargetChipId());
        copy.setTargetIndex(source.getTargetIndex());
        copy.setStatus(source.getStatus());
        copy.setMessage(source.getMessage());
        copy.setImageName(source.getImageName());
        copy.setPhotoUrl(source.getPhotoUrl());
        copy.setCreateTime(source.getCreateTime());
        return copy;
    }

    private void releaseCaptureSlots(DeviceCamCaptureTaskRespVO task) {
        releaseCaptureSlots(task.getTaskId(), task.getCamChipId());
    }

    private void releaseCaptureSlots(String operationId, String camChipId) {
        String sliderLampChipId = captureSliderLampByTask.remove(operationId);
        if (notBlank(sliderLampChipId)) {
            activeCaptureTaskBySliderLamp.remove(sliderLampChipId, operationId);
        }
        String captureControllerChipId = captureControllerByTask.remove(operationId);
        if (notBlank(captureControllerChipId)) {
            activeCaptureTaskByCaptureController.remove(captureControllerChipId, operationId);
        }
        activeCaptureTaskByCam.remove(camChipId, operationId);
    }

    private void failCaptureTask(DeviceCamCaptureTaskRespVO task, String status, String message, Long storeId) {
        if (notBlank(task.getBatchId())) {
            failBatchPhysicalTask(task, status, message);
            return;
        }
        task.setStatus(status);
        task.setMessage(message);
        captureUploadTokens.remove(task.getTaskId());
        pendingCaptureMotions.remove(task.getTaskId());
        pendingSingleReturns.remove(task.getTaskId());
        releaseRetainedCollisionGuards(task.getTaskId());
        releaseCaptureSlots(task);
        webSocketPushService.pushCamCaptureResult(task, storeId);
        scheduleTaskCleanup(task.getTaskId());
    }

    @PreDestroy
    public void shutdownCaptureTimeoutExecutor() {
        captureAiExecutor.shutdownNow();
        try {
            captureAiExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        captureTimeoutExecutor.shutdownNow();
    }

    @Override
    public DeviceLampClothStateRespVO reportLampClothState(DeviceLampClothStateReqVO reqVO) {
        DeviceDO lamp = requireLampLike(reqVO.getChipId());
        LocalDateTime now = LocalDateTime.now();
        DeviceLampClothStateRespVO previous = clothStateCache.get(lamp.getChipId());
        String clothState = defaultStatus(reqVO.getClothState(), "unknown");
        DeviceLampClothStateRespVO resp = new DeviceLampClothStateRespVO();
        resp.setChipId(lamp.getChipId());
        resp.setClothState(clothState);
        resp.setLastTakenAt("taken".equals(clothState)
                ? now
                : previous == null ? null : previous.getLastTakenAt());
        resp.setTracking(Boolean.TRUE.equals(reqVO.getTracking()));
        resp.setUpdateTime(now);
        clothStateCache.put(lamp.getChipId(), resp);
        webSocketPushService.pushLampClothState(resp, lamp.getStoreId());
        evaluateTrackingForLamp(lamp.getChipId());
        return resp;
    }

    @Override
    public DeviceLampProximityStateRespVO reportLampProximityState(DeviceLampProximityStateReqVO reqVO) {
        DeviceDO lamp = requireLampLike(reqVO.getChipId());
        DeviceLampProximityStateRespVO resp = new DeviceLampProximityStateRespVO();
        resp.setChipId(lamp.getChipId());
        resp.setNearby(Boolean.TRUE.equals(reqVO.getNearby()));
        resp.setUpdateTime(LocalDateTime.now());
        proximityStateCache.put(lamp.getChipId(), resp);
        webSocketPushService.pushLampProximityState(resp, lamp.getStoreId());
        return resp;
    }

    @Override
    public void resetAutomaticGarmentDetection(Long storeId) {
        if (storeId == null) {
            return;
        }
        String batchId = automaticDetectionBatchByStore.remove(storeId);
        if (notBlank(batchId) && !batchId.startsWith("starting:")) {
            failedAutomaticDetectionBatches.remove(batchId);
            discardCaptureBatch(batchId);
        }
        updateGarmentDetectionStatus(storeId, "not_detected", "全部设备已离线");
    }

    @Override
    public void startAutomaticGarmentDetection(Long storeId) {
        if (storeId == null || automaticDetectionBatchByStore.containsKey(storeId)) {
            return;
        }
        List<DeviceDO> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getStoreId, storeId)
        );
        DeviceDO cam = devices == null ? null : devices.stream()
                .filter(device -> DeviceTypeUtil.isCam(device.getDeviceType()))
                .sorted(Comparator.comparing(DeviceDO::getChipId, String.CASE_INSENSITIVE_ORDER))
                .findFirst()
                .orElse(null);
        if (cam == null) {
            updateGarmentDetectionStatus(storeId, "not_detected", "未配置摄像头");
            return;
        }

        String startingToken = "starting:" + UUID.randomUUID();
        if (automaticDetectionBatchByStore.putIfAbsent(storeId, startingToken) != null) {
            return;
        }
        updateGarmentDetectionStatus(storeId, "detecting", "设备已全部在线，开始全区域检测");
        try {
            DeviceCamCaptureBatchRespVO batch = createCaptureBatchForCam(cam);
            if (!automaticDetectionBatchByStore.replace(storeId, startingToken, batch.getBatchId())) {
                discardCaptureBatch(batch.getBatchId());
                return;
            }
            refreshAutomaticDetectionBatch(batch.getBatchId());
        } catch (RuntimeException e) {
            automaticDetectionBatchByStore.remove(storeId, startingToken);
            updateGarmentDetectionStatus(storeId, "not_detected", e.getMessage());
            log.warn("automatic garment detection start failed, storeId={}, camChipId={}",
                    storeId, cam.getChipId(), e);
        }
    }

    @Override
    public void handleDeviceOnlineStatusChanged(String chipId, boolean online) {
        if (!online && notBlank(chipId)) {
            proximityStateCache.remove(chipId);
            List<String> affectedCams = new java.util.ArrayList<>();
            activeTrackingByCam.values().stream()
                    .filter(session -> sameChipId(chipId, session.camChipId())
                            || sameChipId(chipId, session.lampChipId()))
                    .map(TrackingSession::camChipId)
                    .forEach(affectedCams::add);
            affectedCams.stream().distinct().forEach(camChipId -> {
                try {
                    stopTrackingIfActive(camChipId, "tracking device offline");
                } catch (RuntimeException e) {
                    log.warn("failed to stop tracking after device offline, camChipId={}, chipId={}",
                            camChipId, chipId, e);
                }
            });
        }
    }

    @Override
    public String getGarmentDetectionStatus(Long storeId) {
        return storeId == null
                ? "not_detected"
                : garmentDetectionStatusByStore.getOrDefault(storeId, "not_detected");
    }

    @Override
    public Boolean getLampNearby(String chipId) {
        DeviceLampProximityStateRespVO state = proximityStateCache.get(chipId);
        return state == null ? null : state.getNearby();
    }

    @Override
    public LocalDateTime getLastTakenAt(String chipId) {
        DeviceLampClothStateRespVO state = clothStateCache.get(chipId);
        return state == null ? null : state.getLastTakenAt();
    }

    @Override
    public String getTrackingStatus(String chipId) {
        DeviceTrackingStatusRespVO state = trackingCache.get(chipId);
        return state == null ? null : state.getTrackingStatus();
    }

    private void updateGarmentDetectionStatus(Long storeId, String status, String message) {
        garmentDetectionStatusByStore.put(storeId, status);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("message", defaultStatus(message, status));
        payload.put("updateTime", LocalDateTime.now());
        webSocketPushService.pushGarmentDetectionStatus(payload, storeId);
    }

    @Override
    public DeviceTrackingStatusRespVO reportTrackingStatus(DeviceTrackingStatusReqVO reqVO) {
        DeviceDO device = requireDevice(reqVO.getChipId());
        DeviceTrackingStatusRespVO resp = new DeviceTrackingStatusRespVO();
        resp.setChipId(device.getChipId());
        resp.setRole(defaultStatus(reqVO.getRole(), DeviceTypeUtil.normalize(device.getDeviceType())));
        resp.setTrackingStatus(defaultStatus(reqVO.getTrackingStatus(), "unknown"));
        resp.setCamChipId(reqVO.getCamChipId());
        resp.setLampChipId(reqVO.getLampChipId());
        resp.setTargetIndex(reqVO.getTargetIndex());
        resp.setConfidence(reqVO.getConfidence());
        resp.setSequence(reqVO.getSequence());
        resp.setMessage(reqVO.getMessage());
        resp.setUpdateTime(LocalDateTime.now());

        String terminalCamChipId = null;
        String terminalLampChipId = null;
        boolean clearClothTaken = false;
        if (isTerminalTrackingStatus(resp.getTrackingStatus())) {
            String camChipId = notBlank(resp.getCamChipId()) ? resp.getCamChipId() :
                    ("cam".equals(resp.getRole()) ? resp.getChipId() : null);
            if (notBlank(camChipId)) {
                TrackingSession active = activeTrackingByCam.remove(camChipId);
                String lampChipId = notBlank(resp.getLampChipId())
                        ? resp.getLampChipId()
                        : active == null ? null : active.lampChipId();
                TrackingSource source = active == null ? null : active.source();
                String terminalStatus = defaultStatus(resp.getTrackingStatus(), "unknown")
                        .toLowerCase(Locale.ROOT);
                clearClothTaken = Set.of("lost", "timeout").contains(terminalStatus)
                        || source == TrackingSource.AUTO_TOF;
                terminalCamChipId = camChipId;
                terminalLampChipId = lampChipId;
                resp.setCamChipId(camChipId);
                resp.setLampChipId(lampChipId);
            }
        }

        trackingCache.put(device.getChipId(), resp);
        if (notBlank(resp.getLampChipId())) {
            trackingCache.put(resp.getLampChipId(), resp);
        }
        webSocketPushService.pushTrackingStatus(resp, device.getStoreId());

        if (notBlank(terminalCamChipId)) {
            String terminalStatus = defaultStatus(resp.getTrackingStatus(), "unknown")
                    .toLowerCase(Locale.ROOT);
            String terminalReason = "tracking " + terminalStatus;
            sendLampTrackingStop(
                    terminalLampChipId,
                    terminalCamChipId,
                    terminalReason,
                    clearClothTaken
            );
            if (clearClothTaken) {
                clearLampTakenState(terminalLampChipId, device.getStoreId());
            }
            if (Set.of("lost", "timeout", "error").contains(terminalStatus)) {
                DeviceDO cam = requireCam(terminalCamChipId);
                if (deviceSessionManager.isOnline(cam.getChipId())) {
                    sendCameraReturnCenter(cam, terminalReason);
                }

                TrackingCandidate stopped = new TrackingCandidate();
                stopped.camChipId = cam.getChipId();
                stopped.lampChipId = terminalLampChipId;
                stopped.targetIndex = resp.getTargetIndex();
                stopped.confidence = resp.getConfidence();
                pushTracking(stopped, "stopped", terminalReason, cam.getStoreId());

                DeviceCamStatusRespVO returning = new DeviceCamStatusRespVO();
                returning.setCamChipId(cam.getChipId());
                returning.setWorkStatus("returning_center");
                returning.setMessage(terminalReason);
                returning.setUpdateTime(LocalDateTime.now());
                statusCache.put(cam.getChipId(), returning);
                webSocketPushService.pushCamStatus(returning, cam.getStoreId());
            }
        }
        return resp;
    }

    private void clearLampTakenState(String lampChipId, Long storeId) {
        if (!notBlank(lampChipId)) {
            return;
        }
        DeviceLampClothStateRespVO previous = clothStateCache.get(lampChipId);
        DeviceLampClothStateRespVO cleared = new DeviceLampClothStateRespVO();
        cleared.setChipId(lampChipId);
        cleared.setClothState("on_rack");
        cleared.setLastTakenAt(previous == null ? null : previous.getLastTakenAt());
        cleared.setTracking(false);
        cleared.setUpdateTime(LocalDateTime.now());
        clothStateCache.put(lampChipId, cleared);
        webSocketPushService.pushLampClothState(cleared, storeId);
    }

    private void recoverTrackingCacheFromCamStatus(DeviceDO cam, DeviceCamStatusRespVO status) {
        String workStatus = defaultStatus(status.getWorkStatus(), "unknown")
                .toLowerCase(Locale.ROOT);
        if (!Set.of("monitoring", "presence").contains(workStatus)) {
            return;
        }

        DeviceTrackingStatusRespVO previous = trackingCache.get(cam.getChipId());
        if (previous == null || !isTerminalTrackingStatus(previous.getTrackingStatus())) {
            return;
        }

        activeTrackingByCam.remove(cam.getChipId());
        TrackingCandidate recovered = new TrackingCandidate();
        recovered.camChipId = cam.getChipId();
        recovered.lampChipId = previous.getLampChipId();
        recovered.targetIndex = previous.getTargetIndex();
        recovered.confidence = previous.getConfidence();
        pushTracking(
                recovered,
                "monitoring",
                defaultStatus(status.getMessage(), "camera monitoring resumed"),
                cam.getStoreId()
        );
    }

    private DeviceCamPresenceRespVO emptyPresence(String camChipId) {
        DeviceCamPresenceRespVO resp = new DeviceCamPresenceRespVO();
        resp.setCamChipId(camChipId);
        resp.setWorkStatus("unknown");
        resp.setConfigured(Boolean.TRUE.equals(readRoiConfig(camChipId).getConfigured()));
        return resp;
    }

    private DeviceCamStatusRespVO defaultCamStatus(String camChipId) {
        DeviceCamStatusRespVO resp = new DeviceCamStatusRespVO();
        resp.setCamChipId(camChipId);
        resp.setWorkStatus("monitoring");
        return resp;
    }

    private void recordPresenceDurations(DeviceDO cam, java.util.List<DeviceCamPresenceReqVO.PresenceArea> areas, LocalDateTime now) {
        if (areas == null || areas.isEmpty()) {
            presenceDurationState.keySet().removeIf(key -> key.startsWith(cam.getChipId() + "#"));
            return;
        }

        Set<String> reportedKeys = new HashSet<>();
        for (DeviceCamPresenceReqVO.PresenceArea area : areas) {
            if (area == null) {
                continue;
            }
            String key = presenceKey(cam.getChipId(), area);
            reportedKeys.add(key);

            if (!Boolean.TRUE.equals(area.getPresent()) || !notBlank(area.getTargetChipId())) {
                presenceDurationState.remove(key);
                continue;
            }

            PresenceDurationState previous = presenceDurationState.put(key, new PresenceDurationState(now));
            if (previous == null) {
                continue;
            }

            long durationMs = ChronoUnit.MILLIS.between(previous.updateTime, now);
            if (durationMs <= 0 || durationMs > 300_000L) {
                continue;
            }
            increasePresenceDuration(cam, area.getTargetChipId(), durationMs, now);
        }

        String prefix = cam.getChipId() + "#";
        presenceDurationState.keySet().removeIf(key -> key.startsWith(prefix) && !reportedKeys.contains(key));
    }

    private void increasePresenceDuration(DeviceDO cam, String targetChipId, long durationMs, LocalDateTime now) {
        try {
            DeviceDO target = deviceMapper.selectOne(new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getChipId, targetChipId.trim()).last("limit 1"));
            if (target == null || !isLampLike(target) || target.getStoreId() == null || !target.getStoreId().equals(cam.getStoreId())) {
                return;
            }
            LocalDate statDate = now.toLocalDate();
            durationRecordMapper.insertOrIncrease(target.getId(), target.getStoreId(), target.getChipId(), statDate, durationMs, now);
        } catch (Exception e) {
            log.warn("record cam presence duration failed, camChipId={}, targetChipId={}", cam.getChipId(), targetChipId, e);
        }
    }

    private String presenceKey(String camChipId, DeviceCamPresenceReqVO.PresenceArea area) {
        return camChipId + "#" + normalizeTargetIndex(area.getTargetIndex()) + "#" + defaultStatus(area.getTargetChipId(), "-");
    }

    private void evaluateTrackingForLamp(String lampChipId) {
        presenceCache.values().stream()
                .filter(presence -> presence.getAreas() != null && presence.getAreas().stream()
                        .anyMatch(area -> sameChipId(lampChipId, area.getTargetChipId())))
                .forEach(presence -> evaluateTrackingForCam(presence.getCamChipId()));
    }

    private void evaluateTrackingForCam(String camChipId) {
        DeviceCamPresenceRespVO presence = presenceCache.get(camChipId);
        if (presence == null || presence.getAreas() == null) {
            return;
        }

        DeviceCamRoiConfigVO config = readRoiConfig(camChipId);
        if (!Boolean.TRUE.equals(config.getConfigured())) {
            stopTrackingIfActive(camChipId, "roi not configured");
            return;
        }

        TrackingCandidate candidate = presence.getAreas().stream()
                .filter(area -> Boolean.TRUE.equals(area.getPresent()))
                .map(area -> toTrackingCandidate(camChipId, area, config))
                .filter(candidateValue -> candidateValue != null && isLampTaken(candidateValue.lampChipId))
                .findFirst()
                .orElse(null);

        if (candidate == null) {
            return;
        }

        startTrackingIfNeeded(candidate);
    }

    private TrackingCandidate toTrackingCandidate(String camChipId, DeviceCamPresenceReqVO.PresenceArea area, DeviceCamRoiConfigVO config) {
        if (!notBlank(area.getTargetChipId())) {
            return null;
        }
        DeviceCamRoiItemVO roi = config.getRois().stream()
                .filter(item -> item.getTargetIndex() != null && item.getTargetIndex().equals(normalizeTargetIndex(area.getTargetIndex())))
                .findFirst()
                .orElse(null);
        if (roi == null || !sameChipId(roi.getTargetChipId(), area.getTargetChipId())) {
            return null;
        }
        TrackingCandidate candidate = new TrackingCandidate();
        candidate.camChipId = camChipId;
        candidate.lampChipId = area.getTargetChipId();
        candidate.targetIndex = normalizeTargetIndex(area.getTargetIndex());
        candidate.confidence = area.getConfidence();
        candidate.roi = roi;
        return candidate;
    }

    private DeviceCamRoiItemVO requireConfiguredTrackingTarget(
            DeviceCamRoiConfigVO config,
            int targetIndex,
            String targetChipId) {
        List<DeviceCamRoiItemVO> rois = config.getRois() == null ? List.of() : config.getRois();
        return rois.stream()
                .filter(roi -> roi.getTargetIndex() != null && roi.getTargetIndex() == targetIndex)
                .filter(roi -> sameChipId(roi.getTargetChipId(), targetChipId))
                .findFirst()
                .orElseThrow(() -> new ServiceException("当前区域未绑定该目标灯"));
    }

    private TrackingCandidate trackingCandidate(String camChipId, String lampChipId, int targetIndex) {
        TrackingCandidate candidate = new TrackingCandidate();
        candidate.camChipId = camChipId;
        candidate.lampChipId = lampChipId;
        candidate.targetIndex = targetIndex;
        return candidate;
    }

    private boolean isLampTaken(String lampChipId) {
        DeviceLampClothStateRespVO clothState = clothStateCache.get(lampChipId);
        return clothState != null && "taken".equals(defaultStatus(clothState.getClothState(), "unknown"));
    }

    private void startTrackingIfNeeded(TrackingCandidate candidate) {
        startTrackingIfNeeded(
                candidate,
                TrackingSource.AUTO_TOF,
                "presence + cloth taken, tracking started"
        );
    }

    private void startTrackingIfNeeded(
            TrackingCandidate candidate,
            TrackingSource source,
            String successMessage) {
        DeviceDO cam = requireCam(candidate.camChipId);
        DeviceDO lamp = requireLampLike(candidate.lampChipId);
        if (!deviceSessionManager.isOnline(cam.getChipId()) || !deviceSessionManager.isOnline(lamp.getChipId())) {
            stopTrackingIfActive(candidate.camChipId, "cam or lamp offline");
            return;
        }

        String lampIp = normalizeLampIp(lamp.getIp());
        if (lampIp == null) {
            stopTrackingIfActive(candidate.camChipId, "target lamp IP is missing or invalid");
            pushTracking(candidate, "error", "target lamp IP is missing or invalid", cam.getStoreId());
            return;
        }

        String requestedKey = trackingKey(candidate.lampChipId, candidate.targetIndex);
        TrackingSession active = activeTrackingByCam.get(candidate.camChipId);
        if (active != null && requestedKey.equals(active.trackingKey())) {
            pushTracking(candidate, "tracking", "tracking condition still active", cam.getStoreId());
            return;
        }
        if (active != null) {
            stopTrackingIfActive(candidate.camChipId, "tracking target changed");
        }
        String camCommand = buildCameraStartTrackingCommand(
                cam.getChipId(),
                lamp.getChipId(),
                candidate.targetIndex,
                lampIp
        );
        if (!sendLampTrackingStart(lamp.getChipId(), cam.getChipId(), candidate.targetIndex)) {
            pushTracking(candidate, "error", "lamp tracking lock command send failed", cam.getStoreId());
            return;
        }
        if (!deviceSessionManager.sendToDevice(cam.getChipId(), camCommand)) {
            sendLampTrackingStop(
                    lamp.getChipId(),
                    cam.getChipId(),
                    "camera tracking command send failed",
                    source == TrackingSource.AUTO_TOF
            );
            if (source == TrackingSource.AUTO_TOF) {
                clearLampTakenState(lamp.getChipId(), cam.getStoreId());
            }
            pushTracking(candidate, "error", "camera tracking command send failed", cam.getStoreId());
            return;
        }

        activeTrackingByCam.put(cam.getChipId(), new TrackingSession(
                cam.getChipId(),
                lamp.getChipId(),
                candidate.targetIndex,
                source
        ));
        pushTracking(candidate, "tracking", successMessage, cam.getStoreId());
    }

    String buildCameraStartTrackingCommand(
            String camChipId,
            String targetChipId,
            Integer targetIndex,
            String lampIp) {
        String normalizedLampIp = normalizeLampIp(lampIp);
        if (normalizedLampIp == null) {
            throw new ServiceException("目标灯 IP 缺失或格式无效");
        }
        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "cameraStartTracking");
        command.put("camChipId", camChipId);
        command.put("targetChipId", targetChipId);
        command.put("targetIndex", normalizeTargetIndex(targetIndex));
        command.put("transport", "http");
        command.put("lampIp", normalizedLampIp);
        return command.toString();
    }

    private CollisionGuardSession prepareCollisionGuards(
            DeviceCamRoiConfigVO config, TimedSliderMotion motionPlan, String operationId) {
        SliderCollisionPlanner.CollisionPlan plan = SliderCollisionPlanner.plan(
                motionPlan.currentPositionMm(), motionPlan.targetPositionMm(), config.getRois());
        if (plan.lamps().isEmpty()) return new CollisionGuardSession(operationId, List.of(), 0L);
        java.util.ArrayList<String> parked = new java.util.ArrayList<>();
        CollisionGuardSession session = new CollisionGuardSession(
                operationId,
                plan.lamps().stream().map(SliderCollisionPlanner.GuardedLamp::chipId).toList(),
                plan.parkDelayMs());
        try {
            for (SliderCollisionPlanner.GuardedLamp lamp : plan.lamps()) {
                if (!deviceSessionManager.isOnline(lamp.chipId())) {
                    throw new ServiceException("碰撞避让灯离线：" + lamp.chipId());
                }
                ObjectNode command = objectMapper.createObjectNode();
                command.put("type", "lampCollisionGuard");
                command.put("action", "park");
                command.put("guardId", operationId);
                command.put("pan", 0D);
                command.put("tilt", 0D);
                command.put("estimatedParkTimeMs", lamp.parkTimeMs());
                command.put("nanoFeedback", false);
                if (!deviceSessionManager.sendToDevice(lamp.chipId(), command.toString())) {
                    throw new ServiceException("碰撞避让指令发送失败：" + lamp.chipId());
                }
                parked.add(lamp.chipId());
            }
            return session;
        } catch (RuntimeException exception) {
            releaseCollisionGuards(new CollisionGuardSession(operationId, List.copyOf(parked), 0L));
            throw exception;
        }
    }

    private void sendSliderMotionCommand(String lampChipId, double targetMm, String source, String taskId) {
        ObjectNode motion = objectMapper.createObjectNode();
        motion.put("type", "arm_position");
        motion.put("source", source);
        motion.put("taskId", taskId);
        motion.put("slider", targetMm);
        if (!deviceSessionManager.sendToDevice(lampChipId, motion.toString())) {
            throw new ServiceException("滑轨对位指令发送失败");
        }
    }

    private void retainCollisionGuards(String operationId, CollisionGuardSession session) {
        if (session.lampChipIds().isEmpty()) {
            return;
        }
        retainedCaptureGuards.compute(operationId, (key, current) -> {
            if (current == null) {
                return session;
            }
            Set<String> lampChipIds = new HashSet<>(current.lampChipIds());
            lampChipIds.addAll(session.lampChipIds());
            return new CollisionGuardSession(
                    operationId,
                    List.copyOf(lampChipIds),
                    Math.max(current.parkDelayMs(), session.parkDelayMs())
            );
        });
    }

    private void releaseRetainedCollisionGuards(String operationId) {
        CollisionGuardSession session = retainedCaptureGuards.remove(operationId);
        if (session != null) {
            releaseCollisionGuards(session);
        }
    }

    private void releaseBatchCollisionGuards(CaptureBatchContext context) {
        for (DeviceCamCaptureTaskRespVO task : context.batch().getTasks()) {
            releaseRetainedCollisionGuards(task.getTaskId());
        }
        releaseRetainedCollisionGuards(context.batch().getBatchId());
    }

    private void releaseCollisionGuards(CollisionGuardSession session) {
        for (String lampChipId : session.lampChipIds()) {
            try {
                ObjectNode command = objectMapper.createObjectNode();
                command.put("type", "lampCollisionGuard");
                command.put("action", "release");
                command.put("guardId", session.guardId());
                deviceSessionManager.sendToDevice(lampChipId, command.toString());
            } catch (RuntimeException exception) {
                log.warn("collision guard release failed, guardId={}, lampChipId={}",
                        session.guardId(), lampChipId, exception);
            }
        }
    }

    private DeviceDO requireSliderLampForCurrentStore(DeviceCamRoiConfigVO config) {
        if (!notBlank(config.getSliderLampChipId())) {
            throw new ServiceException("请先配置滑轨控制灯");
        }
        return requireLampLikeForCurrentStore(config.getSliderLampChipId());
    }

    private DeviceDO requireSliderLamp(DeviceCamRoiConfigVO config) {
        if (!notBlank(config.getSliderLampChipId())) {
            throw new ServiceException("请先配置滑轨控制灯");
        }
        return requireLampLike(config.getSliderLampChipId());
    }

    private void stopTrackingIfActive(String camChipId, String reason) {
        TrackingSession active = activeTrackingByCam.remove(camChipId);
        if (active == null) {
            return;
        }

        String lampChipId = active.lampChipId();
        int targetIndex = active.targetIndex();
        TrackingSource source = active.source();

        DeviceDO cam = requireCam(camChipId);
        sendLampTrackingStop(
                lampChipId,
                cam.getChipId(),
                reason,
                source == TrackingSource.AUTO_TOF
        );
        if (source == TrackingSource.AUTO_TOF) {
            clearLampTakenState(lampChipId, cam.getStoreId());
        }
        if (deviceSessionManager.isOnline(cam.getChipId())) {
            sendCameraReturnCenter(cam, reason);
        }

        TrackingCandidate candidate = new TrackingCandidate();
        candidate.camChipId = cam.getChipId();
        candidate.lampChipId = lampChipId;
        candidate.targetIndex = targetIndex;
        pushTracking(candidate, "stopped", reason, cam.getStoreId());
        webSocketPushService.pushCamStatus(Map.of(
                "camChipId", cam.getChipId(),
                "workStatus", "returning_center",
                "message", reason
        ), cam.getStoreId());
    }

    private void sendCameraReturnCenter(DeviceDO cam, String reason) {
        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "cameraReturnCenter");
        command.put("camChipId", cam.getChipId());
        command.put("reason", reason);
        deviceSessionManager.sendToDevice(cam.getChipId(), command.toString());
    }

    private boolean sendLampTrackingStart(String lampChipId, String camChipId, Integer targetIndex) {
        if (!notBlank(lampChipId)) {
            return false;
        }
        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "lampTrackingStart");
        command.put("lampChipId", lampChipId);
        command.put("camChipId", camChipId);
        command.put("targetIndex", normalizeTargetIndex(targetIndex));
        return deviceSessionManager.sendToDevice(lampChipId, command.toString());
    }

    private void sendLampTrackingStop(
            String lampChipId,
            String camChipId,
            String reason,
            boolean clearClothTaken) {
        if (!notBlank(lampChipId)) {
            return;
        }
        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "lampTrackingStop");
        command.put("lampChipId", lampChipId);
        if (notBlank(camChipId)) {
            command.put("camChipId", camChipId);
        }
        command.put("reason", defaultStatus(reason, "tracking stopped"));
        if (clearClothTaken) {
            command.put("clearClothTaken", true);
        }
        if (!deviceSessionManager.sendToDevice(lampChipId, command.toString())) {
            log.warn("lamp tracking stop command send failed, lampChipId={}, camChipId={}, reason={}",
                    lampChipId, camChipId, reason);
        }
    }

    private String trackingKey(String lampChipId, Integer targetIndex) {
        return lampChipId + "#" + normalizeTargetIndex(targetIndex);
    }

    private void pushTracking(TrackingCandidate candidate, String status, String message, Long storeId) {
        DeviceTrackingStatusRespVO resp = new DeviceTrackingStatusRespVO();
        resp.setChipId(candidate.camChipId);
        resp.setRole("cam");
        resp.setTrackingStatus(status);
        resp.setCamChipId(candidate.camChipId);
        resp.setLampChipId(candidate.lampChipId);
        resp.setTargetIndex(candidate.targetIndex);
        resp.setConfidence(candidate.confidence);
        resp.setMessage(message);
        resp.setUpdateTime(LocalDateTime.now());
        trackingCache.put(candidate.camChipId, resp);
        if (notBlank(candidate.lampChipId)) {
            trackingCache.put(candidate.lampChipId, resp);
        }
        webSocketPushService.pushTrackingStatus(resp, storeId);
    }

    private boolean sameChipId(String left, String right) {
        return notBlank(left) && notBlank(right) && left.trim().equalsIgnoreCase(right.trim());
    }

    private boolean isTerminalTrackingStatus(String status) {
        if (!notBlank(status)) {
            return false;
        }
        return Set.of("error", "lost", "stopped", "timeout")
                .contains(status.trim().toLowerCase(Locale.ROOT));
    }

    private String normalizeLampIp(String value) {
        if (!notBlank(value)) {
            return null;
        }
        String candidate = value.trim();
        String[] segments = candidate.split("\\.", -1);
        if (segments.length != 4) {
            return null;
        }
        for (String segment : segments) {
            if (segment.isEmpty() || !segment.chars().allMatch(Character::isDigit)) {
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

    private Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static class TrackingCandidate {
        private String camChipId;
        private String lampChipId;
        private Integer targetIndex;
        private Double confidence;
        private DeviceCamRoiItemVO roi;
    }

    private enum TrackingSource {
        AUTO_TOF,
        MANUAL
    }

    private record TrackingSession(
            String camChipId,
            String lampChipId,
            int targetIndex,
            TrackingSource source
    ) {
        private String trackingKey() {
            return lampChipId + "#" + targetIndex;
        }
    }

    private record PendingCaptureMotion(
            String camChipId,
            String sliderLampChipId,
            String captureControllerChipId,
            double capturePan,
            double captureTilt,
            String targetChipId,
            int targetIndex,
            double sliderTargetMm,
            String uploadToken,
            Long storeId
    ) {
    }

    private record BatchCaptureTarget(
            int targetIndex,
            String targetChipId,
            double sliderTargetMm
    ) {
    }

    private record TimedSliderMotion(
            double currentPositionMm,
            double targetPositionMm,
            String speedMode,
            long delayMs
    ) {
    }

    private record SliderCalibration(double distanceMm, double timeSeconds) {
    }

    private record CollisionGuardSession(String guardId, List<String> lampChipIds, long parkDelayMs) {
    }

    private record PendingBatchReturn(
            String sliderLampChipId,
            double targetMm,
            Long storeId
    ) {
    }

    private record PendingSingleReturn(
            String camChipId,
            String sliderLampChipId,
            double targetMm,
            Long storeId
    ) {
    }

    private static final class CaptureBatchContext {
        private final DeviceCamCaptureBatchRespVO batch;
        private final List<BatchCaptureTarget> targets;
        private final String sliderLampChipId;
        private final String captureControllerChipId;
        private final Long storeId;
        private final DeviceCamRoiConfigVO config;
        private final double standbySliderMm;
        private int currentIndex = -1;

        private CaptureBatchContext(
                DeviceCamCaptureBatchRespVO batch,
                List<BatchCaptureTarget> targets,
                String sliderLampChipId,
                String captureControllerChipId,
                Long storeId,
                DeviceCamRoiConfigVO config,
                double standbySliderMm) {
            this.batch = batch;
            this.targets = targets;
            this.sliderLampChipId = sliderLampChipId;
            this.captureControllerChipId = captureControllerChipId;
            this.storeId = storeId;
            this.config = config;
            this.standbySliderMm = standbySliderMm;
        }

        private DeviceCamCaptureBatchRespVO batch() {
            return batch;
        }

        private List<BatchCaptureTarget> targets() {
            return targets;
        }

        private String sliderLampChipId() {
            return sliderLampChipId;
        }

        private String captureControllerChipId() {
            return captureControllerChipId;
        }

        private Long storeId() {
            return storeId;
        }

        private DeviceCamRoiConfigVO config() {
            return config;
        }

        private double standbySliderMm() {
            return standbySliderMm;
        }

        private int currentIndex() {
            return currentIndex;
        }

        private void setCurrentIndex(int currentIndex) {
            this.currentIndex = currentIndex;
        }
    }

    private static final class StoredImageMultipartFile implements MultipartFile {
        private final Path path;
        private final String originalFilename;
        private final String contentType;

        private StoredImageMultipartFile(Path path, String originalFilename, String contentType) {
            this.path = path;
            this.originalFilename = originalFilename == null || originalFilename.isBlank()
                    ? path.getFileName().toString()
                    : originalFilename;
            this.contentType = contentType;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            try {
                return !Files.isRegularFile(path) || Files.size(path) == 0L;
            } catch (IOException ignored) {
                return true;
            }
        }

        @Override
        public long getSize() {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return 0L;
            }
        }

        @Override
        public byte[] getBytes() throws IOException {
            return Files.readAllBytes(path);
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return Files.newInputStream(path);
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            Files.copy(path, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record PresenceDurationState(LocalDateTime updateTime) {
    }

    DeviceCamRoiConfigVO normalizeConfig(String camChipId, DeviceCamRoiConfigVO config) {
        DeviceCamRoiConfigVO value = config == null ? new DeviceCamRoiConfigVO() : config;
        value.setCamChipId(camChipId);
        value.setSliderLampChipId(notBlank(value.getSliderLampChipId())
                ? value.getSliderLampChipId().trim()
                : null);
        value.setCaptureControllerChipId(notBlank(value.getCaptureControllerChipId())
                ? value.getCaptureControllerChipId().trim()
                : null);
        Double legacyCapturePan = value.getCapturePan();
        Double legacyCaptureTilt = value.getCaptureTilt();
        value.setGarmentCapturePan(clampDouble(
                value.getGarmentCapturePan() != null ? value.getGarmentCapturePan() : legacyCapturePan,
                0D, 180D, 90D
        ));
        value.setGarmentCaptureTilt(clampDouble(
                value.getGarmentCaptureTilt() != null ? value.getGarmentCaptureTilt() : legacyCaptureTilt,
                0D, 180D, 90D
        ));
        value.setPersonCapturePan(clampDouble(value.getPersonCapturePan(), 0D, 180D, 90D));
        value.setPersonCaptureTilt(clampDouble(value.getPersonCaptureTilt(), 0D, 180D, 90D));
        value.setFlowUploadEnabled(Boolean.TRUE.equals(value.getFlowUploadEnabled()));
        int flowUploadIntervalSeconds = value.getFlowUploadIntervalSeconds() == null
                ? 30
                : value.getFlowUploadIntervalSeconds();
        value.setFlowUploadIntervalSeconds(Math.max(5, Math.min(3600, flowUploadIntervalSeconds)));
        if (value.getRois() == null) {
            value.setRois(new java.util.ArrayList<>());
        }
        value.getRois().sort(Comparator.comparing(item -> item.getTargetIndex() == null ? 99 : item.getTargetIndex()));
        for (DeviceCamRoiItemVO roi : value.getRois()) {
            roi.setTargetIndex(normalizeTargetIndex(roi.getTargetIndex()));
            roi.setX(clamp01(roi.getX()));
            roi.setY(clamp01(roi.getY()));
            roi.setW(clamp01(roi.getW()));
            roi.setH(clamp01(roi.getH()));
            roi.setGarmentCapturePan(clampDouble(
                    roi.getGarmentCapturePan(), 0D, 180D, value.getGarmentCapturePan()
            ));
            roi.setGarmentCaptureTilt(clampDouble(
                    roi.getGarmentCaptureTilt(), 0D, 180D, value.getGarmentCaptureTilt()
            ));
            roi.setPersonCapturePan(clampDouble(
                    roi.getPersonCapturePan(), 0D, 180D, value.getPersonCapturePan()
            ));
            roi.setPersonCaptureTilt(clampDouble(
                    roi.getPersonCaptureTilt(), 0D, 180D, value.getPersonCaptureTilt()
            ));
            roi.setCollisionCenterMm(clampDouble(roi.getCollisionCenterMm(), 0D, 2500D, 0D));
            roi.setCollisionClearanceMm(clampDouble(roi.getCollisionClearanceMm(), 0D, 2500D, 0D));
            roi.setCollisionParkTimeSeconds(normalizeMoveTime(roi.getCollisionParkTimeSeconds()));
        }
        value.setSliderPresets(normalizeSliderPresetMap(value));
        value.setSliderMoveTimes(normalizeSliderMoveTimeMap(value));
        value.setConfigured(value.getRois().size() >= 3 && value.getRois().stream().limit(3).allMatch(this::isConfiguredRoi));
        return value;
    }

    private Map<String, Double> normalizeSliderPresetMap(DeviceCamRoiConfigVO config) {
        Map<String, Double> normalized = new LinkedHashMap<>();
        for (int targetIndex = 1; targetIndex <= 3; targetIndex++) {
            String key = String.valueOf(targetIndex);
            Double slider = config.getSliderPresets() == null ? null : config.getSliderPresets().get(key);
            if (slider == null) {
                slider = legacySlider(config.getLegacyCapturePresets(), key);
            }
            if (slider == null) {
                slider = legacySlider(config.getLegacyTrackingPresets(), key);
            }
            normalized.put(key, (double) Math.round(clampDouble(slider, 0D, 2500D, 0D)));
        }
        Double standby = config.getSliderPresets() == null ? null : config.getSliderPresets().get("standby");
        normalized.put("standby", (double) Math.round(clampDouble(standby, 0D, 2500D, 0D)));
        return normalized;
    }

    private Map<String, DeviceCamSliderMoveTimeVO> normalizeSliderMoveTimeMap(DeviceCamRoiConfigVO config) {
        Map<String, DeviceCamSliderMoveTimeVO> normalized = new LinkedHashMap<>();
        Map<String, DeviceCamSliderMoveTimeVO> source = config.getSliderMoveTimes();
        for (int targetIndex = 1; targetIndex <= BATCH_TARGET_COUNT; targetIndex++) {
            String key = String.valueOf(targetIndex);
            DeviceCamSliderMoveTimeVO input = source == null ? null : source.get(key);
            DeviceCamSliderMoveTimeVO output = new DeviceCamSliderMoveTimeVO();
            output.setSlow(normalizeMoveTime(input == null ? null : input.getSlow()));
            output.setNormal(normalizeMoveTime(input == null ? null : input.getNormal()));
            output.setFast(normalizeMoveTime(input == null ? null : input.getFast()));
            normalized.put(key, output);
        }
        DeviceCamSliderMoveTimeVO standbyInput = source == null ? null : source.get("standby");
        DeviceCamSliderMoveTimeVO standbyOutput = new DeviceCamSliderMoveTimeVO();
        standbyOutput.setSlow(normalizeMoveTime(standbyInput == null ? null : standbyInput.getSlow()));
        standbyOutput.setNormal(normalizeMoveTime(standbyInput == null ? null : standbyInput.getNormal()));
        standbyOutput.setFast(normalizeMoveTime(standbyInput == null ? null : standbyInput.getFast()));
        normalized.put("standby", standbyOutput);
        return normalized;
    }

    private double normalizeMoveTime(Double value) {
        double normalized = clampDouble(value, 0D, 3600D, 0D);
        return Math.round(normalized * 1000D) / 1000D;
    }

    private Double legacySlider(
            Map<String, com.genius.smartlight.vo.device.DeviceCamPresetVO> presets,
            String key) {
        if (presets == null || presets.get(key) == null) {
            return null;
        }
        return presets.get(key).getSlider();
    }

    private DeviceCamRoiItemVO requireCaptureRoi(DeviceCamRoiConfigVO config, Integer targetIndex) {
        int normalizedTargetIndex = normalizeTargetIndex(targetIndex);
        return config.getRois().stream()
                .filter(roi -> roi.getTargetIndex() != null && roi.getTargetIndex() == normalizedTargetIndex)
                .findFirst()
                .orElseThrow(() -> new ServiceException("区域 " + normalizedTargetIndex + " 拍摄角度未配置"));
    }

    private double resolveSliderPreset(DeviceCamRoiConfigVO config, Integer targetIndex) {
        String key = String.valueOf(normalizeTargetIndex(targetIndex));
        Map<String, Double> presets = config.getSliderPresets();
        return (double) Math.round(clampDouble(
                presets == null ? null : presets.get(key),
                0D,
                2500D,
                0D
        ));
    }

    private SliderCalibration resolveSliderCalibration(
            DeviceCamRoiConfigVO config,
            Integer targetIndex,
            String speedMode) {
        String key = String.valueOf(normalizeTargetIndex(targetIndex));
        double targetDistanceMm = resolveSliderPreset(config, targetIndex);
        Double targetTimeSeconds = sliderMoveTime(config, key, speedMode);
        if (targetDistanceMm > 0D && isPositiveFinite(targetTimeSeconds)) {
            return new SliderCalibration(targetDistanceMm, targetTimeSeconds);
        }
        if (targetDistanceMm > 0D) {
            throw new ServiceException("请先填写区域 " + key + " 的 " + speedMode + " 滑轨移动时间");
        }

        SliderCalibration fallback = null;
        for (int index = 1; index <= BATCH_TARGET_COUNT; index++) {
            String candidateKey = String.valueOf(index);
            double candidateDistanceMm = resolveSliderPreset(config, index);
            Double candidateTimeSeconds = sliderMoveTime(config, candidateKey, speedMode);
            if (candidateDistanceMm <= 0D || !isPositiveFinite(candidateTimeSeconds)) {
                continue;
            }
            if (fallback == null || candidateDistanceMm > fallback.distanceMm()) {
                fallback = new SliderCalibration(candidateDistanceMm, candidateTimeSeconds);
            }
        }
        if (fallback == null) {
            throw new ServiceException("目标为 0 mm 时，请至少填写一个非零区域的 " + speedMode + " 滑轨移动时间");
        }
        return fallback;
    }

    private Double sliderMoveTime(DeviceCamRoiConfigVO config, String key, String speedMode) {
        DeviceCamSliderMoveTimeVO times = config.getSliderMoveTimes() == null
                ? null
                : config.getSliderMoveTimes().get(key);
        return times == null ? null : times.timeFor(speedMode);
    }

    private boolean isPositiveFinite(Double value) {
        return value != null && Double.isFinite(value) && value > 0D;
    }

    private boolean isConfiguredRoi(DeviceCamRoiItemVO roi) {
        return notBlank(roi.getTargetChipId()) && roi.getW() != null && roi.getW() > 0 && roi.getH() != null && roi.getH() > 0;
    }

    private DeviceCamRoiConfigVO readRoiConfig(String camChipId) {
        Path path = configPath(camChipId);
        if (!Files.exists(path)) {
            DeviceCamRoiConfigVO empty = new DeviceCamRoiConfigVO();
            empty.setCamChipId(camChipId);
            empty.setSliderPresets(normalizeSliderPresetMap(empty));
            empty.setSliderMoveTimes(normalizeSliderMoveTimeMap(empty));
            empty.setConfigured(false);
            return empty;
        }
        try {
            return normalizeConfig(camChipId, objectMapper.readValue(path.toFile(), DeviceCamRoiConfigVO.class));
        } catch (IOException e) {
            throw new ServiceException("读取 cam ROI 配置失败");
        }
    }

    private void writeRoiConfig(DeviceCamRoiConfigVO config) {
        try {
            Files.createDirectories(CAM_CONFIG_DIR);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath(config.getCamChipId()).toFile(), config);
        } catch (IOException e) {
            throw new ServiceException("保存 cam ROI 配置失败");
        }
    }

    private Path configPath(String chipId) {
        return CAM_CONFIG_DIR.resolve(safeName(chipId) + ".json").normalize();
    }

    private String saveUpload(MultipartFile file, String kind, String nameSeed) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("上传图片不能为空");
        }
        String filename = safeName(nameSeed) + "_" + LocalDateTime.now().format(TS) + "_" + safeName(file.getOriginalFilename());
        Path dir = CAM_UPLOAD_DIR.resolve(kind).normalize();
        Path target = dir.resolve(filename).normalize();
        if (!target.startsWith(CAM_UPLOAD_DIR)) {
            throw new ServiceException("上传路径非法");
        }
        try {
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return kind + "/" + filename;
        } catch (IOException e) {
            throw new ServiceException("保存上传图片失败");
        }
    }

    private String resolveTargetChipId(String camChipId, Integer targetIndex, String explicitTargetChipId) {
        if (notBlank(explicitTargetChipId)) {
            return explicitTargetChipId.trim();
        }
        int index = normalizeTargetIndex(targetIndex);
        return readRoiConfig(camChipId).getRois().stream()
                .filter(roi -> roi.getTargetIndex() != null && roi.getTargetIndex() == index)
                .map(DeviceCamRoiItemVO::getTargetChipId)
                .filter(this::notBlank)
                .findFirst()
                .orElseThrow(() -> new ServiceException("目标灯缺失，请先完成 ROI 标定"));
    }

    private int resolveCaptureTargetIndex(
            String camChipId,
            Integer requestedTargetIndex,
            String targetChipId) {
        List<CameraCapturePresetResolver.TargetBinding> bindings =
                readRoiConfig(camChipId).getRois().stream()
                        .map(roi -> new CameraCapturePresetResolver.TargetBinding(
                                roi.getTargetIndex(),
                                roi.getTargetChipId()
                        ))
                        .toList();
        return CameraCapturePresetResolver.resolve(
                        requestedTargetIndex,
                        targetChipId,
                        bindings
                )
                .orElseThrow(() -> new ServiceException(
                        "未配置所选 Lamp 的 Camera 滑轨预设，请先在 Camera 详情中绑定目标灯并设置滑轨预设"
                ));
    }

    private DeviceDO requireCamForCurrentStore(String chipId) {
        DeviceDO device = requireDeviceForCurrentStore(chipId);
        if (!DeviceTypeUtil.isCam(device.getDeviceType())) {
            throw new ServiceException("设备不是 cam");
        }
        return device;
    }

    private DeviceDO requireLampLikeForCurrentStore(String chipId) {
        DeviceDO device = requireDeviceForCurrentStore(chipId);
        if (!isLampLike(device)) {
            throw new ServiceException("目标设备必须是 lamp 或 camlamp");
        }
        return device;
    }

    private DeviceDO requireCaptureControllerForCurrentStore(String chipId) {
        DeviceDO device = requireDeviceForCurrentStore(chipId);
        if (!DeviceTypeUtil.isCaptureController(device.getDeviceType())) {
            throw new ServiceException("拍照控制器必须是 cam_capture 设备");
        }
        return device;
    }

    private DeviceDO requireDeviceForCurrentStore(String chipId) {
        DeviceDO device = requireDevice(chipId);
        Long storeId = currentStoreService.getCurrentStoreId();
        if (device.getStoreId() == null || !device.getStoreId().equals(storeId)) {
            throw new ServiceException("无权操作该设备");
        }
        return device;
    }

    private DeviceDO requireCam(String chipId) {
        DeviceDO device = requireDevice(chipId);
        if (!DeviceTypeUtil.isCam(device.getDeviceType())) {
            throw new ServiceException("设备不是 cam");
        }
        return device;
    }

    private DeviceDO requireLampLike(String chipId) {
        DeviceDO device = requireDevice(chipId);
        if (!isLampLike(device)) {
            throw new ServiceException("设备不是 lamp/camlamp");
        }
        return device;
    }

    private DeviceDO requireCaptureController(String chipId) {
        DeviceDO device = requireDevice(chipId);
        if (!DeviceTypeUtil.isCaptureController(device.getDeviceType())) {
            throw new ServiceException("设备不是 cam_capture");
        }
        return device;
    }

    private DeviceDO requireCaptureControllerForCurrentStore(DeviceCamRoiConfigVO config) {
        if (config == null || !notBlank(config.getCaptureControllerChipId())) {
            throw new ServiceException("拍照控制器未绑定，请先在 Camera 详情中选择 cam_capture 设备");
        }
        return requireCaptureControllerForCurrentStore(config.getCaptureControllerChipId());
    }

    private DeviceDO requireCaptureController(DeviceCamRoiConfigVO config) {
        if (config == null || !notBlank(config.getCaptureControllerChipId())) {
            throw new ServiceException("拍照控制器未绑定，请先在 Camera 详情中选择 cam_capture 设备");
        }
        return requireCaptureController(config.getCaptureControllerChipId());
    }

    private void requireSameStore(DeviceDO expectedStoreDevice, DeviceDO candidate) {
        if (expectedStoreDevice.getStoreId() == null
                || candidate.getStoreId() == null
                || !expectedStoreDevice.getStoreId().equals(candidate.getStoreId())) {
            throw new ServiceException("设备不属于同一门店");
        }
    }

    private DeviceDO requireDevice(String chipId) {
        if (!notBlank(chipId)) {
            throw new ServiceException("chipId 不能为空");
        }
        DeviceDO device = deviceMapper.selectOne(new LambdaQueryWrapper<DeviceDO>().eq(DeviceDO::getChipId, chipId.trim()));
        if (device == null) {
            throw new ServiceException("设备不存在");
        }
        return device;
    }

    private boolean isLampLike(DeviceDO device) {
        return DeviceTypeUtil.isLampLike(device.getDeviceType());
    }

    private String defaultStatus(String value, String fallback) {
        return notBlank(value) ? value.trim() : fallback;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private int normalizeTargetIndex(Integer value) {
        return clampInt(value, 1, 3, 1);
    }

    private int clampInt(Integer value, int min, int max, int fallback) {
        int next = value == null ? fallback : value;
        return Math.max(min, Math.min(max, next));
    }

    private double clampDouble(Double value, double min, double max, double fallback) {
        double next = value == null || !Double.isFinite(value) ? fallback : value;
        return Math.max(min, Math.min(max, next));
    }

    private Double clamp01(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, value));
    }

    private String safeName(String value) {
        String text = value == null || value.isBlank() ? "unknown" : value.trim();
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private LocalDateTime parseTime(String value) {
        if (!notBlank(value)) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception ignoredAgain) {
                return LocalDateTime.now();
            }
        }
    }
}
