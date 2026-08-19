package com.genius.smartlight.controller.admin.device;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.device.GarmentAimCalibrationService;
import com.genius.smartlight.vo.device.DeviceGarmentAimCalibrationCopyReqVO;
import com.genius.smartlight.vo.device.DeviceGarmentAimCalibrationMigrationReqVO;
import com.genius.smartlight.vo.device.DeviceGarmentAimCalibrationRespVO;
import com.genius.smartlight.vo.device.DeviceGarmentAimCalibrationSampleReqVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "服装照射标定", description = "采集服装图像坐标与 Lamp 电机位置并拟合现场模型")
@RestController
@RequestMapping("/admin/device/lamp/{lampChipId}/garment-aim-calibration")
@RequiredArgsConstructor
public class DeviceGarmentAimCalibrationController {

    private final GarmentAimCalibrationService calibrationService;

    @Operation(summary = "读取服装照射标定状态")
    @GetMapping
    public CommonResult<DeviceGarmentAimCalibrationRespVO> get(
            @PathVariable String lampChipId,
            @RequestParam(required = false) String sourceKey) {
        return CommonResult.success(calibrationService.getCalibration(lampChipId, sourceKey));
    }

    @Operation(summary = "用指定拍摄来源的最新服装识别结果和已调准电机位置新增标定样本")
    @PostMapping("/samples")
    public CommonResult<DeviceGarmentAimCalibrationRespVO> addSample(
            @PathVariable String lampChipId,
            @RequestParam(required = false) String sourceKey,
            @Valid @RequestBody DeviceGarmentAimCalibrationSampleReqVO reqVO) {
        return CommonResult.success(calibrationService.addSample(lampChipId, sourceKey, reqVO));
    }

    @Operation(summary = "清空指定拍摄来源的服装照射标定样本和模型")
    @DeleteMapping
    public CommonResult<DeviceGarmentAimCalibrationRespVO> clear(
            @PathVariable String lampChipId,
            @RequestParam(required = false) String sourceKey) {
        return CommonResult.success(calibrationService.clearCalibration(lampChipId, sourceKey));
    }

    @Operation(summary = "将旧版未区分拍摄来源的标定数据迁移到指定拍摄设备")
    @PostMapping("/migrate-legacy")
    public CommonResult<DeviceGarmentAimCalibrationRespVO> migrateLegacy(
            @PathVariable String lampChipId,
            @Valid @RequestBody DeviceGarmentAimCalibrationMigrationReqVO reqVO) {
        return CommonResult.success(calibrationService.migrateLegacy(lampChipId, reqVO));
    }

    @Operation(summary = "把当前 Lamp 的某个拍摄来源标定复制到其他 Lamp")
    @PostMapping("/copy")
    public CommonResult<Boolean> copy(
            @PathVariable String lampChipId,
            @Valid @RequestBody DeviceGarmentAimCalibrationCopyReqVO reqVO) {
        calibrationService.copyCalibration(lampChipId, reqVO);
        return CommonResult.success(true);
    }
}
