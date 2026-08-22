package com.genius.smartlight.service.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.service.store.CurrentStoreService;
import com.genius.smartlight.vo.device.DeviceCamCaptureConfigVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureTargetVO;
import com.genius.smartlight.vo.device.DeviceCamRoiConfigVO;
import com.genius.smartlight.vo.device.DeviceCamSliderMoveTimeVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceCamCaptureConfigServiceTest {

    private static final String CAM_CHIP_ID = "CAM-INTEGRATION-CONFIG-TEST";
    @Test
    void save_preservesCaptureControllerPosesFlowAndCollisionParkTime() {
        DeviceMapper deviceMapper = mock(DeviceMapper.class);
        CurrentStoreService currentStoreService = mock(CurrentStoreService.class);
        DeviceCamService deviceCamService = mock(DeviceCamService.class);
        when(currentStoreService.getCurrentStoreId()).thenReturn(1L);

        DeviceDO cam = device(CAM_CHIP_ID, "cam");
        DeviceDO controller = device("CAPTURE-001", "cam_capture");
        DeviceDO lamp1 = device("LAMP-001", "lamp");
        DeviceDO lamp2 = device("LAMP-002", "lamp");
        DeviceDO lamp3 = device("LAMP-003", "lamp");
        when(deviceMapper.selectOne(any())).thenReturn(
                cam,
                controller,
                lamp1,
                lamp1,
                lamp2,
                lamp3
        );

        DeviceCamCaptureConfigService service = new DeviceCamCaptureConfigService(
                deviceMapper,
                currentStoreService,
                new ObjectMapper(),
                deviceCamService
        );

        DeviceCamCaptureConfigVO input = new DeviceCamCaptureConfigVO();
        input.setCamChipId("IGNORED-BY-SERVER");
        input.setSliderLampChipId("LAMP-001");
        input.setCaptureControllerChipId("CAPTURE-001");
        input.setFlowUploadEnabled(true);
        input.setFlowUploadIntervalSeconds(45);
        input.setTargets(List.of(
                target(1, "LAMP-001", 100D, 81D, 82D, 91D, 92D, 1.1D),
                target(2, "LAMP-002", 900D, 83D, 84D, 93D, 94D, 1.2D),
                target(3, "LAMP-003", 1700D, 85D, 86D, 95D, 96D, 1.3D)
        ));

        DeviceCamCaptureConfigVO saved = service.saveForCurrentStore(CAM_CHIP_ID, input);

        ArgumentCaptor<DeviceCamRoiConfigVO> compatibility =
                ArgumentCaptor.forClass(DeviceCamRoiConfigVO.class);
        verify(deviceCamService).saveRoiConfig(
                org.mockito.ArgumentMatchers.eq(CAM_CHIP_ID), compatibility.capture());
        DeviceCamRoiConfigVO legacy = compatibility.getValue();

        assertEquals("CAPTURE-001", saved.getCaptureControllerChipId());
        assertEquals("CAPTURE-001", legacy.getCaptureControllerChipId());
        assertTrue(legacy.getFlowUploadEnabled());
        assertEquals(45, legacy.getFlowUploadIntervalSeconds());
        assertEquals(81D, legacy.getRois().get(0).getGarmentCapturePan());
        assertEquals(92D, legacy.getRois().get(0).getPersonCaptureTilt());
        assertEquals(1.3D, legacy.getRois().get(2).getCollisionParkTimeSeconds());
    }

    private DeviceCamCaptureTargetVO target(
            int index,
            String lampChipId,
            double sliderMm,
            double garmentPan,
            double garmentTilt,
            double personPan,
            double personTilt,
            double parkSeconds) {
        DeviceCamCaptureTargetVO target = new DeviceCamCaptureTargetVO();
        target.setIndex(index);
        target.setLampChipId(lampChipId);
        target.setSliderMm(sliderMm);
        target.setGarmentCapturePan(garmentPan);
        target.setGarmentCaptureTilt(garmentTilt);
        target.setPersonCapturePan(personPan);
        target.setPersonCaptureTilt(personTilt);
        target.setCollisionParkTimeSeconds(parkSeconds);
        DeviceCamSliderMoveTimeVO times = new DeviceCamSliderMoveTimeVO();
        times.setSlow(20D);
        times.setNormal(10D);
        times.setFast(5D);
        target.setMoveTimes(times);
        return target;
    }

    private DeviceDO device(String chipId, String deviceType) {
        DeviceDO device = new DeviceDO();
        device.setChipId(chipId);
        device.setDeviceType(deviceType);
        device.setStoreId(1L);
        return device;
    }
}
