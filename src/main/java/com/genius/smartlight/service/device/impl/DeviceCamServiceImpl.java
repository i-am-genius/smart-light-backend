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
import com.genius.smartlight.vo.device.DeviceCamStatusReqVO;
import com.genius.smartlight.vo.device.DeviceCamStatusRespVO;
import com.genius.smartlight.vo.device.DeviceCamTrackingControlReqVO;
import com.genius.smartlight.vo.device.DeviceLampClothStateReqVO;
import com.genius.smartlight.vo.device.DeviceLampClothStateRespVO;
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
    private static final int MOTION_TIMEOUT_SECONDS = 45;
    private static final int CAPTURE_TIMEOUT_SECONDS = 45;
    private static final int BATCH_TARGET_COUNT = 3;
    private static final double SLIDER_ARRIVAL_TOLERANCE_MM = 0.05D;

    private final DeviceMapper deviceMapper;
    private final CurrentStoreService currentStoreService;
    private final WebSocketPushService webSocketPushService;
    private final DeviceSessionManager deviceSessionManager;
    private final PersonFlowRecordService personFlowRecordService;
    private final DurationRecordMapper durationRecordMapper;
    private final AiService aiService;
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
    private final Map<String, DeviceTrackingStatusRespVO> trackingCache = new ConcurrentHashMap<>();
    private final Map<String, String> activeTrackingByCam = new ConcurrentHashMap<>();
    private final Map<String, PresenceDurationState> presenceDurationState = new ConcurrentHashMap<>();
    private final Map<String, String> captureUploadTokens = new ConcurrentHashMap<>();
    private final Map<String, PendingCaptureMotion> pendingCaptureMotions = new ConcurrentHashMap<>();
    private final Map<String, String> activeCaptureTaskBySliderLamp = new ConcurrentHashMap<>();
    private final Map<String, String> activeCaptureTaskByCam = new ConcurrentHashMap<>();
    private final Map<String, String> captureSliderLampByTask = new ConcurrentHashMap<>();
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
    public DeviceCamRoiConfigVO saveRoiConfig(String camChipId, DeviceCamRoiConfigVO config) {
        DeviceDO cam = requireCamForCurrentStore(camChipId);
        DeviceCamRoiConfigVO normalized = normalizeConfig(cam.getChipId(), config);
        if (notBlank(normalized.getSliderLampChipId())) {
            requireLampLikeForCurrentStore(normalized.getSliderLampChipId());
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

        String requestedKey = lamp.getChipId() + "#" + targetIndex;
        String activeKey = activeTrackingByCam.get(cam.getChipId());
        if (notBlank(activeKey) && !requestedKey.equals(activeKey)) {
            stopTrackingIfActive(cam.getChipId(), "manual tracking target changed");
        }

        TrackingCandidate candidate = trackingCandidate(cam.getChipId(), lamp.getChipId(), targetIndex);
        startTrackingIfNeeded(candidate, config, "manual tracking started");
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
        sendLampTrackingStop(lamp.getChipId(), cam.getChipId(), "manual tracking stopped");
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
        if (!deviceSessionManager.isOnline(cam.getChipId())) {
            throw new ServiceException("cam 离线，无法创建拍摄任务");
        }
        String targetChipId = resolveTargetChipId(cam.getChipId(), reqVO.getTargetIndex(), reqVO.getTargetChipId());
        DeviceDO target = requireLampLikeForCurrentStore(targetChipId);
        int targetIndex = resolveCaptureTargetIndex(
                cam.getChipId(),
                reqVO.getTargetIndex(),
                target.getChipId()
        );
        DeviceCamRoiConfigVO config = readRoiConfig(cam.getChipId());
        DeviceDO sliderLamp = requireSliderLampForCurrentStore(config);
        if (!deviceSessionManager.isOnline(sliderLamp.getChipId())) {
            throw new ServiceException("滑轨控制灯离线，无法执行滑轨对位");
        }
        double sliderTargetMm = resolveSliderPreset(config, targetIndex);

        DeviceCamCaptureTaskRespVO task = new DeviceCamCaptureTaskRespVO();
        task.setTaskId(UUID.randomUUID().toString());
        task.setCamChipId(cam.getChipId());
        task.setTargetChipId(target.getChipId());
        task.setTargetIndex(targetIndex);
        task.setStatus("waiting_motion");
        task.setMessage("waiting for slider arrival");
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
        captureSliderLampByTask.put(task.getTaskId(), sliderLamp.getChipId());
        taskCache.put(task.getTaskId(), task);
        String uploadToken = UUID.randomUUID().toString().replace("-", "");
        captureUploadTokens.put(task.getTaskId(), uploadToken);

        PendingCaptureMotion pending = new PendingCaptureMotion(
                cam.getChipId(),
                sliderLamp.getChipId(),
                target.getChipId(),
                targetIndex,
                sliderTargetMm,
                uploadToken,
                cam.getStoreId()
        );
        pendingCaptureMotions.put(task.getTaskId(), pending);

        ObjectNode motion = objectMapper.createObjectNode();
        motion.put("type", "arm_position");
        motion.put("source", "camera_capture");
        motion.put("taskId", task.getTaskId());
        motion.put("slider", sliderTargetMm);
        boolean motionSent;
        try {
            motionSent = deviceSessionManager.sendToDevice(sliderLamp.getChipId(), motion.toString());
        } catch (RuntimeException e) {
            discardCaptureTask(task);
            throw e;
        }
        if (!motionSent) {
            discardCaptureTask(task);
            throw new ServiceException("滑轨对位指令发送失败");
        }

        webSocketPushService.pushCamCaptureTask(task, cam.getStoreId());
        scheduleMotionTimeout(task.getTaskId());
        return task;
    }

    @Override
    public DeviceCamCaptureBatchRespVO createCaptureBatch(DeviceCamCaptureBatchReqVO reqVO) {
        DeviceDO cam = requireCamForCurrentStore(reqVO.getCamChipId());
        if (!deviceSessionManager.isOnline(cam.getChipId())) {
            throw new ServiceException("cam 离线，无法创建批量拍摄任务");
        }
        DeviceCamRoiConfigVO config = readRoiConfig(cam.getChipId());
        DeviceDO sliderLamp = requireSliderLampForCurrentStore(config);
        if (!deviceSessionManager.isOnline(sliderLamp.getChipId())) {
            throw new ServiceException("滑轨控制灯离线，无法执行批量拍摄");
        }

        List<BatchCaptureTarget> targets = buildBatchCaptureTargets(config);
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
        captureSliderLampByTask.put(batchId, sliderLamp.getChipId());
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

            double targetTwoMm = targets.stream()
                    .filter(target -> target.targetIndex() == 2)
                    .mapToDouble(BatchCaptureTarget::sliderTargetMm)
                    .findFirst()
                    .orElseThrow(() -> new ServiceException("区域 2 滑轨预设缺失"));
            CaptureBatchContext context = new CaptureBatchContext(
                    batch,
                    targets,
                    sliderLamp.getChipId(),
                    cam.getStoreId(),
                    targetTwoMm
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
        String taskId = notBlank(reqVO.getTaskId()) ? reqVO.getTaskId().trim() : null;
        if (taskId == null || !notBlank(reqVO.getChipId()) || reqVO.getTargetMm() == null) {
            return;
        }

        PendingBatchReturn pendingReturn = pendingBatchReturns.get(taskId);
        if (pendingReturn != null) {
            handleBatchReturnArrival(reqVO, taskId, pendingReturn);
            return;
        }

        PendingCaptureMotion pending = pendingCaptureMotions.get(taskId);
        DeviceCamCaptureTaskRespVO task = taskCache.get(taskId);
        if (pending == null || task == null || !"waiting_motion".equals(task.getStatus())) {
            return;
        }
        if (!sameChipId(reqVO.getChipId(), pending.sliderLampChipId())) {
            log.warn("slider arrival ignored: lamp mismatch, taskId={}, expected={}, actual={}",
                    taskId, pending.sliderLampChipId(), reqVO.getChipId());
            return;
        }
        if (Math.abs(reqVO.getTargetMm() - pending.sliderTargetMm()) > SLIDER_ARRIVAL_TOLERANCE_MM) {
            log.warn("slider arrival ignored: target mismatch, taskId={}, expected={}, actual={}",
                    taskId, pending.sliderTargetMm(), reqVO.getTargetMm());
            return;
        }
        if (!pendingCaptureMotions.remove(taskId, pending)) {
            return;
        }

        if (!deviceSessionManager.isOnline(pending.camChipId())) {
            failCaptureTask(task, "camera_offline", "slider arrived but camera is offline", pending.storeId());
            return;
        }

        ObjectNode capture = objectMapper.createObjectNode();
        capture.put("type", "cameraCapture");
        capture.put("taskId", taskId);
        capture.put("camChipId", pending.camChipId());
        capture.put("targetChipId", pending.targetChipId());
        capture.put("targetIndex", pending.targetIndex());
        capture.put("motionReady", true);
        capture.put("uploadUrl", "/device/cam/capture-task/" + taskId + "/photo");
        capture.put("uploadToken", pending.uploadToken());
        boolean captureSent;
        try {
            captureSent = deviceSessionManager.sendToDevice(pending.camChipId(), capture.toString());
        } catch (RuntimeException e) {
            log.warn("camera capture command send failed, taskId={}", taskId, e);
            failCaptureTask(task, "camera_command_failed", "camera capture command send failed", pending.storeId());
            return;
        }
        if (!captureSent) {
            failCaptureTask(task, "camera_command_failed", "camera capture command send failed", pending.storeId());
            return;
        }

        task.setStatus("capturing");
        task.setMessage("slider arrived; capture command sent");
        webSocketPushService.pushCamCaptureTask(task, pending.storeId());
        scheduleCaptureTimeout(taskId);
    }

    private List<BatchCaptureTarget> buildBatchCaptureTargets(DeviceCamRoiConfigVO config) {
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
            DeviceDO target = requireLampLikeForCurrentStore(roi.getTargetChipId());
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
            task.setMessage("waiting for slider arrival");
        }

        String uploadToken = captureUploadTokens.get(task.getTaskId());
        PendingCaptureMotion pending = new PendingCaptureMotion(
                task.getCamChipId(),
                context.sliderLampChipId(),
                target.targetChipId(),
                target.targetIndex(),
                target.sliderTargetMm(),
                uploadToken,
                context.storeId()
        );
        pendingCaptureMotions.put(task.getTaskId(), pending);

        ObjectNode motion = objectMapper.createObjectNode();
        motion.put("type", "arm_position");
        motion.put("source", "camera_capture");
        motion.put("taskId", task.getTaskId());
        motion.put("slider", target.sliderTargetMm());
        boolean sent;
        try {
            sent = deviceSessionManager.sendToDevice(context.sliderLampChipId(), motion.toString());
        } catch (RuntimeException e) {
            log.warn("batch slider command failed, batchId={}, taskId={}",
                    context.batch().getBatchId(), task.getTaskId(), e);
            sent = false;
        }
        if (!sent) {
            pendingCaptureMotions.remove(task.getTaskId(), pending);
            failBatchPhysicalTask(task, "motion_command_failed", "slider command send failed");
            return;
        }
        webSocketPushService.pushCamCaptureTask(task, context.storeId());
        scheduleMotionTimeout(task.getTaskId());
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
        advanceCaptureBatch(task);
    }

    private void startBatchReturn(CaptureBatchContext context) {
        DeviceCamCaptureBatchRespVO batch = context.batch();
        synchronized (context) {
            if (!"running".equals(batch.getStatus())) {
                return;
            }
            batch.setStatus("returning_target_2");
            batch.setMessage("all photos received; returning to target 2");
        }

        PendingBatchReturn pending = new PendingBatchReturn(
                context.sliderLampChipId(),
                context.targetTwoMm(),
                context.storeId()
        );
        pendingBatchReturns.put(batch.getBatchId(), pending);
        webSocketPushService.pushCamStatus(Map.of(
                "camChipId", batch.getCamChipId(),
                "workStatus", "returning_target_2",
                "batchId", batch.getBatchId(),
                "message", "三张图片已收到，滑轨返回区域 2"
        ), context.storeId());

        ObjectNode motion = objectMapper.createObjectNode();
        motion.put("type", "arm_position");
        motion.put("source", "camera_batch_return");
        motion.put("taskId", batch.getBatchId());
        motion.put("slider", context.targetTwoMm());
        boolean sent;
        try {
            sent = deviceSessionManager.sendToDevice(context.sliderLampChipId(), motion.toString());
        } catch (RuntimeException e) {
            log.warn("batch return command failed, batchId={}", batch.getBatchId(), e);
            sent = false;
        }
        if (!sent) {
            pendingBatchReturns.remove(batch.getBatchId(), pending);
            finishCaptureBatch(context, "return_failed", "failed to return to target 2", "error");
            return;
        }
        scheduleBatchReturnTimeout(batch.getBatchId());
    }

    private void handleBatchReturnArrival(
            DeviceSliderStatusReqVO reqVO,
            String batchId,
            PendingBatchReturn pending) {
        if (!sameChipId(reqVO.getChipId(), pending.sliderLampChipId())
                || Math.abs(reqVO.getTargetMm() - pending.targetMm()) > SLIDER_ARRIVAL_TOLERANCE_MM
                || !pendingBatchReturns.remove(batchId, pending)) {
            return;
        }
        CaptureBatchContext context = batchContexts.get(batchId);
        if (context != null) {
            finishCaptureBatch(context, "completed", "batch capture completed at target 2", "batch_complete");
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
        releaseCaptureSlots(batch.getBatchId(), batch.getCamChipId());
        webSocketPushService.pushCamStatus(Map.of(
                "camChipId", batch.getCamChipId(),
                "workStatus", camWorkStatus,
                "batchId", batch.getBatchId(),
                "message", message
        ), context.storeId());
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
            releaseCaptureSlots(task);
        }
        enqueueCaptureAi(task, cam.getStoreId(), storedPath, originalFilename, contentType);
        return receivedSnapshot;
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
        scheduleTaskCleanup(task.getTaskId());
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
    public void uploadFlowPhotoByDevice(String camChipId, String token, Integer personCount, Double confidence, String detectTime, MultipartFile file) {
        if (!deviceSessionManager.validateUploadToken(camChipId, token)) {
            throw new ServiceException("cam flow upload token invalid");
        }
        uploadFlowPhoto(camChipId, personCount, confidence, detectTime, file);
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

    private void scheduleMotionTimeout(String taskId) {
        captureTimeoutExecutor.schedule(() -> timeoutMotionTask(taskId), MOTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void scheduleCaptureTimeout(String taskId) {
        captureTimeoutExecutor.schedule(() -> timeoutCaptureTask(taskId), CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void scheduleBatchReturnTimeout(String batchId) {
        captureTimeoutExecutor.schedule(() -> timeoutBatchReturn(batchId), MOTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void scheduleTaskCleanup(String taskId) {
        // 任务终态后延迟 5 分钟清理，给前端足够时间查询状态
        captureTimeoutExecutor.schedule(() -> {
            DeviceCamCaptureTaskRespVO task = taskCache.remove(taskId);
            captureUploadTokens.remove(taskId);
            pendingCaptureMotions.remove(taskId);
            captureBatchByTask.remove(taskId);
            if (task != null) {
                releaseCaptureSlots(task);
            }
        }, 5, TimeUnit.MINUTES);
    }

    private void scheduleBatchCleanup(String batchId) {
        captureTimeoutExecutor.schedule(() -> {
            batchCache.remove(batchId);
            batchContexts.remove(batchId);
            pendingBatchReturns.remove(batchId);
        }, 5, TimeUnit.MINUTES);
    }

    private void timeoutMotionTask(String taskId) {
        DeviceCamCaptureTaskRespVO task = taskCache.get(taskId);
        PendingCaptureMotion pending = pendingCaptureMotions.remove(taskId);
        if (task == null || pending == null || !"waiting_motion".equals(task.getStatus())) {
            return;
        }
        failCaptureTask(task, "motion_timeout", "slider arrival timeout", pending.storeId());
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

    private void timeoutBatchReturn(String batchId) {
        PendingBatchReturn pending = pendingBatchReturns.remove(batchId);
        CaptureBatchContext context = batchContexts.get(batchId);
        if (pending == null || context == null || !"returning_target_2".equals(context.batch().getStatus())) {
            return;
        }
        finishCaptureBatch(context, "return_failed", "return to target 2 timeout", "error");
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
        DeviceCamCaptureBatchRespVO batch = batchCache.get(operationId);
        if (batch != null) {
            return "running".equals(batch.getStatus()) || "returning_target_2".equals(batch.getStatus());
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
        releaseCaptureSlots(task);
    }

    private void discardCaptureBatch(String batchId) {
        DeviceCamCaptureBatchRespVO batch = batchCache.remove(batchId);
        CaptureBatchContext context = batchContexts.remove(batchId);
        pendingBatchReturns.remove(batchId);
        if (batch != null) {
            for (DeviceCamCaptureTaskRespVO task : batch.getTasks()) {
                taskCache.remove(task.getTaskId());
                captureUploadTokens.remove(task.getTaskId());
                pendingCaptureMotions.remove(task.getTaskId());
                captureBatchByTask.remove(task.getTaskId());
            }
        }
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
        DeviceLampClothStateRespVO resp = new DeviceLampClothStateRespVO();
        resp.setChipId(lamp.getChipId());
        resp.setClothState(defaultStatus(reqVO.getClothState(), "unknown"));
        resp.setLastTakenAt(parseTime(reqVO.getLastTakenAt()));
        resp.setTracking(Boolean.TRUE.equals(reqVO.getTracking()));
        resp.setUpdateTime(LocalDateTime.now());
        clothStateCache.put(lamp.getChipId(), resp);
        webSocketPushService.pushLampClothState(resp, lamp.getStoreId());
        evaluateTrackingForLamp(lamp.getChipId());
        return resp;
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
        if (isTerminalTrackingStatus(resp.getTrackingStatus())) {
            String camChipId = notBlank(resp.getCamChipId()) ? resp.getCamChipId() :
                    ("cam".equals(resp.getRole()) ? resp.getChipId() : null);
            if (notBlank(camChipId)) {
                String active = activeTrackingByCam.remove(camChipId);
                String lampChipId = notBlank(resp.getLampChipId())
                        ? resp.getLampChipId()
                        : lampChipIdFromActive(active);
                sendLampTrackingStop(lampChipId, camChipId, "tracking " + resp.getTrackingStatus());
            }
        }
        trackingCache.put(device.getChipId(), resp);
        webSocketPushService.pushTrackingStatus(resp, device.getStoreId());
        return resp;
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
            stopTrackingIfActive(camChipId, "presence missing");
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
            stopTrackingIfActive(camChipId, "presence or cloth condition cleared");
            return;
        }

        startTrackingIfNeeded(candidate, config);
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

    private void startTrackingIfNeeded(TrackingCandidate candidate, DeviceCamRoiConfigVO config) {
        startTrackingIfNeeded(candidate, config, "presence + cloth taken, HTTP direct tracking started");
    }

    private void startTrackingIfNeeded(
            TrackingCandidate candidate,
            DeviceCamRoiConfigVO config,
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

        String activeKey = candidate.lampChipId + "#" + candidate.targetIndex;
        if (activeKey.equals(activeTrackingByCam.get(candidate.camChipId))) {
            pushTracking(candidate, "tracking", "tracking condition still active", cam.getStoreId());
            return;
        }

        DeviceDO sliderLamp;
        try {
            sliderLamp = requireSliderLamp(config);
        } catch (ServiceException e) {
            stopTrackingIfActive(candidate.camChipId, "slider lamp is not configured");
            pushTracking(candidate, "error", e.getMessage(), cam.getStoreId());
            return;
        }
        if (!deviceSessionManager.isOnline(sliderLamp.getChipId())) {
            stopTrackingIfActive(candidate.camChipId, "slider lamp offline");
            pushTracking(candidate, "error", "滑轨控制灯离线", cam.getStoreId());
            return;
        }

        double sliderTargetMm = resolveSliderPreset(config, candidate.targetIndex);
        if (!sendSliderPositionToLamp(sliderLamp.getChipId(), sliderTargetMm, "camera_tracking")) {
            stopTrackingIfActive(candidate.camChipId, "slider command send failed");
            pushTracking(candidate, "error", "slider command send failed", cam.getStoreId());
            return;
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
                    "camera tracking command send failed"
            );
            pushTracking(candidate, "error", "camera tracking command send failed", cam.getStoreId());
            return;
        }
        activeTrackingByCam.put(candidate.camChipId, activeKey);

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

    private boolean sendSliderPositionToLamp(String lampChipId, double sliderTargetMm, String source) {
        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "arm_position");
        command.put("source", source);
        command.put("slider", sliderTargetMm);
        return deviceSessionManager.sendToDevice(lampChipId, command.toString());
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
        String active = activeTrackingByCam.remove(camChipId);
        if (!notBlank(active)) {
            return;
        }

        String[] parts = active.split("#", 2);
        String lampChipId = parts.length > 0 ? parts[0] : "";
        int targetIndex = parts.length > 1 ? normalizeTargetIndex(parseInt(parts[1])) : 1;

        DeviceDO cam = requireCam(camChipId);
        sendLampTrackingStop(lampChipId, cam.getChipId(), reason);
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

    private void sendLampTrackingStop(String lampChipId, String camChipId, String reason) {
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
        if (!deviceSessionManager.sendToDevice(lampChipId, command.toString())) {
            log.warn("lamp tracking stop command send failed, lampChipId={}, camChipId={}, reason={}",
                    lampChipId, camChipId, reason);
        }
    }

    private String lampChipIdFromActive(String active) {
        if (!notBlank(active)) {
            return null;
        }
        return active.split("#", 2)[0];
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

    private record PendingCaptureMotion(
            String camChipId,
            String sliderLampChipId,
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

    private record PendingBatchReturn(
            String sliderLampChipId,
            double targetMm,
            Long storeId
    ) {
    }

    private static final class CaptureBatchContext {
        private final DeviceCamCaptureBatchRespVO batch;
        private final List<BatchCaptureTarget> targets;
        private final String sliderLampChipId;
        private final Long storeId;
        private final double targetTwoMm;
        private int currentIndex = -1;

        private CaptureBatchContext(
                DeviceCamCaptureBatchRespVO batch,
                List<BatchCaptureTarget> targets,
                String sliderLampChipId,
                Long storeId,
                double targetTwoMm) {
            this.batch = batch;
            this.targets = targets;
            this.sliderLampChipId = sliderLampChipId;
            this.storeId = storeId;
            this.targetTwoMm = targetTwoMm;
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

        private Long storeId() {
            return storeId;
        }

        private double targetTwoMm() {
            return targetTwoMm;
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
        }
        value.setSliderPresets(normalizeSliderPresetMap(value));
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
            normalized.put(key, (double) Math.round(clampDouble(slider, 0D, 1200D, 0D)));
        }
        return normalized;
    }

    private Double legacySlider(
            Map<String, com.genius.smartlight.vo.device.DeviceCamPresetVO> presets,
            String key) {
        if (presets == null || presets.get(key) == null) {
            return null;
        }
        return presets.get(key).getSlider();
    }

    private double resolveSliderPreset(DeviceCamRoiConfigVO config, Integer targetIndex) {
        String key = String.valueOf(normalizeTargetIndex(targetIndex));
        Map<String, Double> presets = config.getSliderPresets();
        return (double) Math.round(clampDouble(
                presets == null ? null : presets.get(key),
                0D,
                1200D,
                0D
        ));
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
