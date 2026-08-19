package com.genius.smartlight.service.device.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.service.device.CaptureLightingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class CamCaptureLightingAspect {

    private final CaptureLightingService captureLightingService;
    private final ObjectMapper objectMapper;

    @Around("execution(* com.genius.smartlight.websocket.DeviceSessionManager.sendToDevice(..)) && args(chipId,payload)")
    public Object standardizeBeforeCameraCapture(
            ProceedingJoinPoint joinPoint,
            String chipId,
            String payload) throws Throwable {
        JsonNode root = parse(payload);
        if (root == null || !"cameraCapture".equals(root.path("type").asText())) {
            return joinPoint.proceed();
        }

        String targetLampChipId = root.path("targetChipId").asText("").trim();
        if (!StringUtils.hasText(targetLampChipId)) {
            return joinPoint.proceed();
        }

        boolean started = false;
        try {
            captureLightingService.startStandard(targetLampChipId);
            started = true;
            sleepSettleTime();
            Object result = joinPoint.proceed();
            if (result instanceof Boolean sent && !sent) {
                captureLightingService.stop(targetLampChipId);
            }
            return result;
        } catch (Throwable throwable) {
            if (started) {
                try {
                    captureLightingService.stop(targetLampChipId);
                } catch (RuntimeException stopException) {
                    log.warn("failed to stop capture lighting after camera command error, lamp={}",
                            targetLampChipId, stopException);
                }
            }
            throw throwable;
        }
    }

    private JsonNode parse(String payload) {
        if (!StringUtils.hasText(payload)) {
            return null;
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception exception) {
            return null;
        }
    }

    private void sleepSettleTime() {
        long settleMs = captureLightingService.getSettleMs();
        if (settleMs <= 0) {
            return;
        }
        try {
            Thread.sleep(settleMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
