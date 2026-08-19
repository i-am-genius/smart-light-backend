package com.genius.smartlight.service.device;

import com.genius.smartlight.service.ai.GarmentAimCalibrationFitter;
import com.genius.smartlight.service.ai.GarmentAimTarget;
import com.genius.smartlight.vo.device.DeviceGarmentAimCalibrationCopyReqVO;
import com.genius.smartlight.vo.device.DeviceGarmentAimCalibrationMigrationReqVO;
import com.genius.smartlight.vo.device.DeviceGarmentAimCalibrationRespVO;
import com.genius.smartlight.vo.device.DeviceGarmentAimCalibrationSampleReqVO;

import java.util.Optional;

public interface GarmentAimCalibrationService {

    DeviceGarmentAimCalibrationRespVO getCalibration(String lampChipId);

    DeviceGarmentAimCalibrationRespVO getCalibration(String lampChipId, String sourceKey);

    DeviceGarmentAimCalibrationRespVO addSample(
            String lampChipId,
            DeviceGarmentAimCalibrationSampleReqVO reqVO);

    DeviceGarmentAimCalibrationRespVO addSample(
            String lampChipId,
            String sourceKey,
            DeviceGarmentAimCalibrationSampleReqVO reqVO);

    DeviceGarmentAimCalibrationRespVO clearCalibration(String lampChipId);

    DeviceGarmentAimCalibrationRespVO clearCalibration(String lampChipId, String sourceKey);

    DeviceGarmentAimCalibrationRespVO migrateLegacy(
            String lampChipId,
            DeviceGarmentAimCalibrationMigrationReqVO reqVO);

    void copyCalibration(
            String lampChipId,
            DeviceGarmentAimCalibrationCopyReqVO reqVO);

    Optional<GarmentAimCalibrationFitter.Pose> predict(
            String lampChipId,
            GarmentAimTarget target);

    Optional<GarmentAimCalibrationFitter.Pose> predict(
            String lampChipId,
            String sourceKey,
            GarmentAimTarget target);
}
