package com.genius.smartlight.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.service.device.OtaProgressStore;
import com.genius.smartlight.vo.ai.FabricRecognizeRespVO;
import com.genius.smartlight.vo.ai.PersonDetectRespVO;
import com.genius.smartlight.vo.device.DeviceOnlineStatusRespVO;
import com.genius.smartlight.vo.device.DeviceRespVO;
import com.genius.smartlight.vo.duration.DurationRespVO;
import com.genius.smartlight.vo.lighteffect.LightEffectStateRespVO;
import com.genius.smartlight.vo.lux.LuxRespVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketPushService {

    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;
    private final DeviceSessionManager deviceSessionManager;
    private final OtaProgressStore otaProgressStore;

    public void pushState(DeviceRespVO data) {
        Long storeId = data.getStoreId();
        if (storeId == null) {
            log.warn("pushState skipped: DeviceRespVO has no storeId, chipId={}", data.getChipId());
            return;
        }
        broadcastToStore(storeId, "state", otaProgressStore.applyProgress(data));
    }

    public void pushStateToDevice(String chipId, DeviceRespVO data) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("chipId", chipId);
            payload.put("brightness", data.getBrightness());
            payload.put("temp", data.getTemp());
            payload.put("autoMode", data.getAutoMode());
            payload.put("recommendedBrightness", data.getRecommendedBrightness());
            payload.put("recommendedTemp", data.getRecommendedTemp());
            payload.put("fabric", data.getFabric());
            payload.put("mainColorRgb", data.getMainColorRgb());

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
        } catch (Exception e) {
            log.error("Device state push error, chipId={}", chipId, e);
        }
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
        data.put("originalImagePath", result.getOriginalImagePath());
        data.put("annotatedImagePath", result.getAnnotatedImagePath());
        data.put("combinedImagePath", result.getCombinedImagePath());
        data.put("originalImageUrl", result.getOriginalImageUrl());
        data.put("annotatedImageUrl", result.getAnnotatedImageUrl());
        data.put("combinedImageUrl", result.getCombinedImageUrl());

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

    public void pushAnnounce(String chipId, String ip, String deviceType, Boolean added, Long storeId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("chipId", chipId);
        data.put("ip", ip);
        data.put("deviceType", deviceType);
        data.put("added", added);

        if (storeId != null) {
            broadcastToStore(storeId, "announce", data);
            return;
        }

        try {
            String payload = objectMapper.writeValueAsString(WsMessage.of("announce", data));
            sessionManager.broadcastAll(payload);
        } catch (Exception e) {
            log.error("WebSocket broadcastAll failed, type=announce (unbound device)", e);
        }
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
            String payload = objectMapper.writeValueAsString(WsMessage.of(type, data));
            sessionManager.broadcastToStore(storeId, payload);
        } catch (Exception e) {
            log.error("WebSocket broadcastToStore failed, type={} storeId={}", type, storeId, e);
        }
    }

    private String preview(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}
