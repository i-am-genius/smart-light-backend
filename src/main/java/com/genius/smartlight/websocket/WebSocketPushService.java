package com.genius.smartlight.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.service.ai.GarmentAimCalibrationFitter;
import com.genius.smartlight.service.ai.GarmentAimTarget;
import com.genius.smartlight.service.device.GarmentAimCalibrationService;
import com.genius.smartlight.service.device.OtaProgressStore;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import com.genius.smartlight.vo.ai.GarmentResultSnapshot;
import com.genius.smartlight.vo.ai.PersonDetectRespVO;
import com.genius.smartlight.vo.device.DeviceOnlineStatusRespVO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.vo.duration.DurationRespVO;
import com.genius.smartlight.vo.lighteffect.LightEffectStateRespVO;
import com.genius.smartlight.vo.lux.LuxRespVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketPushService {

    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private final DeviceSessionManager deviceSessionManager;
    private final OtaProgressStore otaProgressStore;
    private final GarmentAimCalibrationService garmentAimCalibrationService;

    public void pushState(DeviceRespVO data) {
        long startNs = System.nanoTime();
        Long storeId = data.getStoreId();
        if (storeId == null) {
            log.warn("pushState skipped: DeviceRespVO has no storeId, chipId={}", data.getChipId());
            log.debug("[STATE-REPORT-PERF] chipId={} step=pushWs.skipNoStore cost={}ms",
                    data.getChipId(), elapsedMs(startNs));
            return;
        }
        long stepStartNs = System.nanoTime();
        DeviceRespVO payload = otaProgressStore.applyProgress(data);
        log.debug("[STATE-REPORT-PERF] chipId={} step=pushWs.applyProgress cost={}ms",
                data.getChipId(), elapsedMs(stepStartNs));
        stepStartNs = System.nanoTime();
        broadcastToStore(storeId, "state", payload);
        log.debug("[STATE-REPORT-PERF] chipId={} step=pushWs.broadcast cost={}ms storeId={}",
                data.getChipId(), elapsedMs(stepStartNs), storeId);
        log.debug("[STATE-REPORT-PERF] chipId={} step=pushWs.total cost={}ms",
                data.getChipId(), elapsedMs(startNs));
    }

    public void pushStateToDevice(String chipId, DeviceRespVO data) {
        pushStateToDevice(chipId, data, null);
    }

    public void pushStateToDevice(String chipId, DeviceRespVO data, String sourceKey) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("chipId", chipId);
            payload.put("brightness", data.getBrightness());
            payload.put("temp", data.getTemp());
            payload.put("autoMode", data.getAutoMode());
            payload.put("garmentAimEnabled", Boolean.TRUE.equals(data.getGarmentAimEnabled()));
            payload.put("garmentDefaultPan", defaultAngle(data.getGarmentDefaultPan(), 0D));
            payload.put("garmentDefaultTilt", defaultAngle(data.getGarmentDefaultTilt(), 20D));
            payload.put("personDefaultPan", defaultAngle(data.getPersonDefaultPan(), 0D));
            payload.put("personDefaultTilt", defaultAngle(data.getPersonDefaultTilt(), -30D));
            payload.put("recommendedBrightness", data.getRecommendedBrightness());
            payload.put("recommendedTemp", data.getRecommendedTemp());
            payload.put("fabric", data.getFabric());
            payload.put("mainColorRgb", data.getMainColorRgb());

            var garmentTarget = GarmentAimTarget.from(data);
            appendGarmentAim(payload, chipId, garmentTarget, Boolean.TRUE.equals(data.getGarmentAimEnabled()), sourceKey);
            sendStatePayload(chipId, payload);
        } catch (Exception e) {
            log.error("Device state push error, chipId={}", chipId, e);
        }
    }

    public void pushGarmentAimToDevice(
            String chipId,
            GarmentResultSnapshot snapshot,
            String sourceKey,
            boolean garmentAimEnabled) {
        if (!StringUtils.hasText(chipId) || snapshot == null) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("chipId", chipId);
            payload.put("garmentAimEnabled", garmentAimEnabled);
            appendGarmentAim(
                    payload,
                    chipId,
                    GarmentAimTarget.from(snapshot),
                    garmentAimEnabled,
                    sourceKey
            );
            sendStatePayload(chipId, payload);
        } catch (Exception exception) {
            log.error("source-aware garment aim push error, chipId={}, sourceKey={}",
                    chipId, sourceKey, exception);
        }
    }

    private void appendGarmentAim(
            Map<String, Object> payload,
            String chipId,
            Optional<GarmentAimTarget> garmentTarget,
            boolean garmentAimEnabled,
            String sourceKey) {
        payload.put("garmentTargetValid", garmentTarget.isPresent());
        payload.put("garmentCalibrationValid", false);
        if (garmentTarget.isEmpty()) {
            return;
        }
        GarmentAimTarget target = garmentTarget.get();
        payload.put("garmentCenterX", target.centerX());
        payload.put("garmentCenterY", target.centerY());
        payload.put("garmentX", target.x());
        payload.put("garmentY", target.y());
        payload.put("garmentW", target.w());
        payload.put("garmentH", target.h());
        payload.put("garmentImageWidth", target.imageWidth());
        payload.put("garmentImageHeight", target.imageHeight());
        if (!garmentAimEnabled) {
            return;
        }

        Optional<GarmentAimCalibrationFitter.Pose> calibratedPose = StringUtils.hasText(sourceKey)
                ? garmentAimCalibrationService.predict(chipId, sourceKey, target)
                : garmentAimCalibrationService.predict(chipId, target);
        calibratedPose.ifPresent(pose -> {
            payload.put("garmentCalibrationValid", true);
            payload.put("garmentAimPan", pose.pan());
            payload.put("garmentAimTilt", pose.tilt());
        });
    }

    private void sendStatePayload(String chipId, Map<String, Object> payload) throws Exception {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "state");
        message.put("data", payload);
        String json = objectMapper.writeValueAsString(message);
        boolean sent = deviceSessionManager.sendToDevice(chipId, json);
        if (!sent) {
            log.warn("Device state push failed, chipId={}, messageType=state", chipId);
            log.debug("Device state push failed payload preview, chipId={}, payload={}", chipId, preview(json));
        } else {
            log.debug("Device state pushed, chipId={}, payload={}", chipId, preview(json));
        }
    }

    private double defaultAngle(Double value, double fallback) {
        return value != null && Double.isFinite(value) ? value : fallback;
    }

    public void pushOnlineStatus(DeviceOnlineStatusRespVO data, Long storeId) {
        broadcastToStore(storeId, "onlineStatus", data);
    }

    public void pushDeviceDeleted(Long id, String chipId, Long storeId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("chipId", chipId);
        broadcastToStore(storeId, "deviceDeleted", data);
    }

    public void pushLux(LuxRespVO data, Long storeId) {
        broadcastToStore(storeId, "lux", data);
    }

    public void pushDuration(DurationRespVO data, Long storeId) {
        broadcastToStore(storeId, "durationUpdate", data);
    }

    public void pushFabricRecognize(String chipId, String filename, FabricRecognizeRespVO result, Long storeId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("chipId", chipId);
        data.put("filename", filename);
        data.put("label", result.getLabel());
        data.put("confidence", result.getConfidence());
        data.put("mainColorRgb", result.getMainColorRgb());
        data.put("recommendedBrightness", result.getRecommendedBrightness());
        data.put("recommendedTemp", result.getRecommendedTemp());
        data.put("clothDetected", result.getClothDetected());
        data.put("clothX", result.getClothX());
        data.put("clothY", result.getClothY());
        data.put("clothW", result.getClothW());
        data.put("clothH", result.getClothH());
        data.put("imageWidth", result.getImageWidth());
        data.put("imageHeight", result.getImageHeight());
        data.put("originalImagePath", result.getOriginalImagePath());
        data.put("annotatedImagePath", result.getAnnotatedImagePath());
        data.put("combinedImagePath", result.getCombinedImagePath());
        data.put("originalImageUrl", result.getOriginalImageUrl());
        data.put("annotatedImageUrl", result.getAnnotatedImageUrl());
        data.put("combinedImageUrl", result.getCombinedImageUrl());
        data.put("resultVersion", result.getResultVersion());
        data.put("segmentationFallback", result.getSegmentationFallback());
        data.put("outfitType", result.getOutfitType());
        data.put("garments", result.getGarments());
        broadcastToStore(storeId, "fabricRecognize", data);
    }

    public void pushPersonDetect(String chipId, String filename, PersonDetectRespVO result, Long storeId, Long recordId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("chipId", chipId);
        data.put("filename", filename);
        data.put("count", result.getCount());
        data.put("confidence", result.getConfidence());
        data.put("timestamp", result.getTimestamp());
        data.put("processingTime", result.getProcessingTime());
        if (recordId != null) {
            data.put("recordId", recordId);
        }
        broadcastToStore(storeId, "personDetection", data);
    }

    public void pushDevicePersonDetection(Object data, Long storeId) {
        broadcastToStore(storeId, "personDetection", data);
    }

    public void pushCamStatus(Object data, Long storeId) {
        broadcastToStore(storeId, "camStatus", data);
    }

    public void pushCamPresence(Object data, Long storeId) {
        broadcastToStore(storeId, "camPresence", data);
    }

    public void pushCamCaptureTask(Object data, Long storeId) {
        broadcastToStore(storeId, "cameraCaptureTask", data);
    }

    public void pushCamCaptureResult(Object data, Long storeId) {
        broadcastToStore(storeId, "cameraCaptureResult", data);
    }

    public void pushLampClothState(Object data, Long storeId) {
        broadcastToStore(storeId, "lampClothState", data);
    }

    public void pushLampProximityState(Object data, Long storeId) {
        broadcastToStore(storeId, "lampProximityState", data);
    }

    public void pushGarmentDetectionStatus(Object data, Long storeId) {
        broadcastToStore(storeId, "garmentDetectionStatus", data);
    }

    public void pushTrackingStatus(Object data, Long storeId) {
        broadcastToStore(storeId, "trackingStatus", data);
    }

    public void pushAnnounce(String chipId, String ip, String deviceType, Boolean added, Long storeId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("chipId", chipId);
        data.put("ip", ip);
        data.put("deviceType", deviceType);
        data.put("added", added);
        if (storeId == null) {
            broadcastAll("announce", data);
            return;
        }
        broadcastToStore(storeId, "announce", data);
    }

    @Deprecated
    public void pushLightEffectState(LightEffectStateRespVO data) {
        log.warn("pushLightEffectState skipped: use pushLightEffectStateToStore(storeId, data)");
    }

    public void pushLightEffectStateToStore(Long storeId, LightEffectStateRespVO data) {
        broadcastToStore(storeId, "lightEffectState", data);
    }

    public boolean pushRawToDevice(String chipId, String message) {
        boolean sent = deviceSessionManager.sendToDevice(chipId, message);
        if (!sent) {
            log.warn("Device command push failed, chipId={}", chipId);
            log.debug("Device command push failed message preview, chipId={}, message={}", chipId, preview(message));
        } else {
            log.debug("Device command pushed, chipId={}, message={}", chipId, preview(message));
        }
        return sent;
    }

    private void broadcastToStore(Long storeId, String type, Object data) {
        if (storeId == null) {
            log.warn("broadcastToStore skipped: storeId is null, type={}", type);
            return;
        }
        try {
            long serializeStartNs = System.nanoTime();
            String payload = objectMapper.writeValueAsString(WsMessage.of(type, data));
            log.debug("[WS-PUSH-PERF] type={} storeId={} step=serialize cost={}ms payloadBytes={}",
                    type, storeId, elapsedMs(serializeStartNs), payload.length());
            long broadcastStartNs = System.nanoTime();
            sessionManager.broadcastToStore(storeId, payload);
            log.debug("[WS-PUSH-PERF] type={} storeId={} step=broadcast cost={}ms",
                    type, storeId, elapsedMs(broadcastStartNs));
        } catch (Exception e) {
            log.error("WebSocket broadcastToStore failed, type={} storeId={}", type, storeId, e);
        }
    }

    private void broadcastAll(String type, Object data) {
        try {
            String payload = objectMapper.writeValueAsString(WsMessage.of(type, data));
            sessionManager.broadcastAll(payload);
        } catch (Exception e) {
            log.error("WebSocket broadcastAll failed, type={}", type, e);
        }
    }

    private String preview(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    private long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }
}
