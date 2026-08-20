package com.genius.smartlight.controller.admin.device;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.device.DeviceCamCaptureConfigService;
import com.genius.smartlight.service.device.DeviceCamService;
import com.genius.smartlight.service.device.FixedPersonTrackingService;
import com.genius.smartlight.vo.device.DeviceCamCaptureBatchReqVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureBatchRespVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureConfigVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskReqVO;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskRespVO;
import com.genius.smartlight.vo.device.DeviceCamPresenceReqVO;
import com.genius.smartlight.vo.device.DeviceCamPresenceRespVO;
import com.genius.smartlight.vo.device.DeviceCamStatusReqVO;
import com.genius.smartlight.vo.device.DeviceCamStatusRespVO;
import com.genius.smartlight.vo.device.DeviceCamTrackingControlReqVO;
import com.genius.smartlight.vo.device.DeviceLampClothStateReqVO;
import com.genius.smartlight.vo.device.DeviceLampClothStateRespVO;
import com.genius.smartlight.vo.device.DeviceTrackingStatusReqVO;
import com.genius.smartlight.vo.device.DeviceTrackingStatusRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.nio.file.Files;

@Tag(name = "cam 视觉节点", description = "独立 cam 设备拍摄、人流与人物追踪接口")
@RestController
@RequestMapping("/admin/device")
@RequiredArgsConstructor
public class DeviceCamController {

    private final DeviceCamService deviceCamService;
    private final DeviceCamCaptureConfigService captureConfigService;
    private final FixedPersonTrackingService fixedPersonTrackingService;

    @Operation(summary = "读取 cam 拍摄对位配置")
    @GetMapping("/cam/{camChipId}/capture-config")
    public CommonResult<DeviceCamCaptureConfigVO> getCaptureConfig(@PathVariable String camChipId) {
        return CommonResult.success(captureConfigService.getForCurrentStore(camChipId));
    }

    @Operation(summary = "保存 cam 拍摄对位配置")
    @PutMapping("/cam/{camChipId}/capture-config")
    public CommonResult<DeviceCamCaptureConfigVO> saveCaptureConfig(
            @PathVariable String camChipId,
            @RequestBody DeviceCamCaptureConfigVO reqVO) {
        return CommonResult.success(captureConfigService.saveForCurrentStore(camChipId, reqVO));
    }

    @Operation(summary = "cam 上报人流 presence；区域归属由 Lamp ToF 提供")
    @PostMapping("/cam/presence")
    public CommonResult<DeviceCamPresenceRespVO> reportPresence(@Valid @RequestBody DeviceCamPresenceReqVO reqVO) {
        return CommonResult.success(deviceCamService.reportPresence(reqVO));
    }

    @Operation(summary = "读取 cam 当前人流 presence 状态")
    @GetMapping("/cam/{camChipId}/presence")
    public CommonResult<DeviceCamPresenceRespVO> getPresence(@PathVariable String camChipId) {
        return CommonResult.success(deviceCamService.getPresence(camChipId));
    }

    @Operation(summary = "cam 上报当前工作状态")
    @PostMapping("/cam/status")
    public CommonResult<DeviceCamStatusRespVO> reportStatus(@Valid @RequestBody DeviceCamStatusReqVO reqVO) {
        return CommonResult.success(deviceCamService.reportStatus(reqVO));
    }

    @Operation(summary = "读取 cam 当前工作状态")
    @GetMapping("/cam/{camChipId}/status")
    public CommonResult<DeviceCamStatusRespVO> getStatus(@PathVariable String camChipId) {
        return CommonResult.success(deviceCamService.getStatus(camChipId));
    }

    @Operation(summary = "手动开始 cam 到目标灯的 UDP 人物追踪")
    @PostMapping("/cam/tracking/start")
    public CommonResult<DeviceTrackingStatusRespVO> startTracking(
            @Valid @RequestBody DeviceCamTrackingControlReqVO reqVO) {
        return CommonResult.success(fixedPersonTrackingService.startManually(reqVO));
    }

    @Operation(summary = "手动停止 cam 人物追踪")
    @PostMapping("/cam/tracking/stop")
    public CommonResult<DeviceTrackingStatusRespVO> stopTracking(
            @Valid @RequestBody DeviceCamTrackingControlReqVO reqVO) {
        return CommonResult.success(fixedPersonTrackingService.stopManually(reqVO));
    }

    @Operation(summary = "创建 cam 服装拍摄任务")
    @PostMapping("/cam/capture-task")
    public CommonResult<DeviceCamCaptureTaskRespVO> createCaptureTask(
            @Valid @RequestBody DeviceCamCaptureTaskReqVO reqVO) {
        return CommonResult.success(deviceCamService.createCaptureTask(reqVO));
    }

    @Operation(summary = "按滑轨位置依次创建三个拍摄目标的 cam 批量拍摄任务")
    @PostMapping("/cam/capture-batch")
    public CommonResult<DeviceCamCaptureBatchRespVO> createCaptureBatch(
            @Valid @RequestBody DeviceCamCaptureBatchReqVO reqVO) {
        return CommonResult.success(deviceCamService.createCaptureBatch(reqVO));
    }

    @Operation(summary = "cam 上传服装拍摄照片")
    @PostMapping(value = "/cam/capture-task/{taskId}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CommonResult<DeviceCamCaptureTaskRespVO> uploadCapturePhoto(
            @PathVariable String taskId,
            @RequestPart("file") MultipartFile file) {
        return CommonResult.success(deviceCamService.uploadCapturePhoto(taskId, file));
    }

    @Operation(summary = "cam 上传人流照片和人数数据")
    @PostMapping(value = "/cam/flow-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CommonResult<Boolean> uploadFlowPhoto(
            @RequestParam String camChipId,
            @RequestParam(required = false) Integer personCount,
            @RequestParam(required = false) Double confidence,
            @RequestParam(required = false) String detectTime,
            @RequestPart("file") MultipartFile file) {
        deviceCamService.uploadFlowPhoto(camChipId, personCount, confidence, detectTime, file);
        return CommonResult.success(true);
    }

    @Operation(summary = "读取 cam 上传图片")
    @GetMapping("/cam/upload/**")
    public ResponseEntity<Resource> getUploadImage(HttpServletRequest request) throws IOException {
        String path = String.valueOf(request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE));
        String prefix = "/admin/device/cam/upload/";
        String imageName = path.startsWith(prefix) ? path.substring(prefix.length()) : "";
        Resource resource = deviceCamService.loadUploadImage(imageName);
        String contentType = Files.probeContentType(resource.getFile().toPath());
        if (contentType == null || !contentType.startsWith("image/")) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .body(resource);
    }

    @Operation(summary = "lamp 上报 ToF 服装取下状态")
    @PostMapping("/lamp/cloth-state")
    public CommonResult<DeviceLampClothStateRespVO> reportLampClothState(
            @Valid @RequestBody DeviceLampClothStateReqVO reqVO) {
        DeviceLampClothStateRespVO result = deviceCamService.reportLampClothState(reqVO);
        fixedPersonTrackingService.onLampClothState(reqVO.getChipId(), reqVO.getClothState());
        return CommonResult.success(result);
    }

    @Operation(summary = "cam/lamp 上报低频追踪状态")
    @PostMapping("/tracking/status")
    public CommonResult<DeviceTrackingStatusRespVO> reportTrackingStatus(
            @Valid @RequestBody DeviceTrackingStatusReqVO reqVO) {
        DeviceTrackingStatusRespVO result = deviceCamService.reportTrackingStatus(reqVO);
        fixedPersonTrackingService.onTrackingStatus(reqVO);
        return CommonResult.success(result);
    }
}
