package com.genius.smartlight.service.device.impl;

import com.genius.smartlight.service.device.GarmentSourceResultService;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskRespVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class CamGarmentSourceResultAspect {

    private final GarmentSourceResultService garmentSourceResultService;

    @After("execution(* com.genius.smartlight.websocket.WebSocketPushService.pushCamCaptureResult(..)) && args(task,storeId)")
    public void persistCameraResult(DeviceCamCaptureTaskRespVO task, Long storeId) {
        if (task == null || !"ai_done".equals(task.getStatus())) {
            return;
        }
        if (!StringUtils.hasText(task.getCamChipId()) || !StringUtils.hasText(task.getTargetChipId())) {
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
