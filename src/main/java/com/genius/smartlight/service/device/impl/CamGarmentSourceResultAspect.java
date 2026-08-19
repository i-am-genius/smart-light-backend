package com.genius.smartlight.service.device.impl;

import com.genius.smartlight.service.device.CaptureLightingService;
import com.genius.smartlight.service.device.GarmentSourceResultService;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskRespVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class CamGarmentSourceResultAspect {

    private static final Set<String> CAPTURE_LIGHT_STOP_STATUSES = Set.of(
            "image_received",
            "upload_failed",
            "timeout",
            "camera_offline",
            "camera_command_failed",
            "motion_config_invalid",
            "motion_command_failed",
            "motion_state_failed"
    );

    private final GarmentSourceResultService garmentSourceResultService;
    private final CaptureLightingService captureLightingService;

    @After("execution(* com.genius.smartlight.websocket.WebSocketPushService.pushCamCaptureResult(..)) && args(task,storeId)")
    public void onCameraCaptureResult(DeviceCamCaptureTaskRespVO task, Long storeId) {
        if (task == null || !StringUtils.hasText(task.getTargetChipId())) {
            return;
        }

        if (CAPTURE_LIGHT_STOP_STATUSES.contains(task.getStatus())) {
            try {
                captureLightingService.stop(task.getTargetChipId());
            } catch (RuntimeException exception) {
                log.warn("camera capture lighting stop failed, taskId={}, targetChipId={}, status={}",
                        task.getTaskId(), task.getTargetChipId(), task.getStatus(), exception);
            }
        }

        if (!"ai_done".equals(task.getStatus()) || !StringUtils.hasText(task.getCamChipId())) {
            return;
        }
        try {
            garmentSourceResultService.saveLatestResult(
                    task.getTargetChipId(),
                    GarmentSourceResultService.camera(task.getCamChipId())
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "camera garment source result persist failed, taskId={}, camChipId={}, targetChipId={}, storeId={}",
                    task.getTaskId(), task.getCamChipId(), task.getTargetChipId(), storeId, exception
            );
        }
    }
}
