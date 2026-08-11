package com.genius.smartlight.service.device;

import com.genius.smartlight.service.ai.GarmentAimCalibrationFitter;
import com.genius.smartlight.service.ai.GarmentAimTarget;
import com.genius.smartlight.vo.device.DeviceGarmentAimCalibrationRespVO;
import com.genius.smartlight.vo.device.DeviceGarmentAimCalibrationSampleReqVO;

import java.util.Optional;

public interface GarmentAimCalibrationService {

    DeviceGarmentAimCalibrationRespVO getCalibration(String lampChipId);

    DeviceGarmentAimCalibrationRespVO addSample(
            String lampChipId,
            DeviceGarmentAimCalibrationSampleReqVO reqVO);

    DeviceGarmentAimCalibrationRespVO clearCalibration(String lampChipId);

    Optional<GarmentAimCalibrationFitter.Pose> predict(
            String lampChipId,
            GarmentAimTarget target);
}
