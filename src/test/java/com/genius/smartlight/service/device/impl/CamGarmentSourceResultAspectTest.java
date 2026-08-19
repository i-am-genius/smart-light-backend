package com.genius.smartlight.service.device.impl;

import com.genius.smartlight.service.device.CaptureLightingService;
import com.genius.smartlight.service.device.GarmentSourceResultService;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskRespVO;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CamGarmentSourceResultAspectTest {

    @Test
    void persistsAiDoneResultUnderExactCameraSource() {
        GarmentSourceResultService sourceService = mock(GarmentSourceResultService.class);
        CaptureLightingService captureLightingService = mock(CaptureLightingService.class);
        CamGarmentSourceResultAspect aspect = new CamGarmentSourceResultAspect(sourceService, captureLightingService);
        DeviceCamCaptureTaskRespVO task = new DeviceCamCaptureTaskRespVO();
        task.setTaskId("task-1");
        task.setCamChipId("CAM-1");
        task.setTargetChipId("LAMP-1");
        task.setStatus("ai_done");

        aspect.onCameraCaptureResult(task, 1L);

        verify(sourceService).saveLatestResult("LAMP-1", "CAMERA:CAM-1");
        verifyNoInteractions(captureLightingService);
    }

    @Test
    void stopsStandardLightingAsSoonAsImageIsReceived() {
        GarmentSourceResultService sourceService = mock(GarmentSourceResultService.class);
        CaptureLightingService captureLightingService = mock(CaptureLightingService.class);
        CamGarmentSourceResultAspect aspect = new CamGarmentSourceResultAspect(sourceService, captureLightingService);
        DeviceCamCaptureTaskRespVO task = new DeviceCamCaptureTaskRespVO();
        task.setTaskId("task-2");
        task.setCamChipId("CAM-1");
        task.setTargetChipId("LAMP-1");
        task.setStatus("image_received");

        aspect.onCameraCaptureResult(task, 1L);

        verify(captureLightingService).stop("LAMP-1");
        verifyNoInteractions(sourceService);
    }
}
