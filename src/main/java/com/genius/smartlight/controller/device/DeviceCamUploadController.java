package com.genius.smartlight.controller.device;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.device.DeviceCamService;
import com.genius.smartlight.vo.device.DeviceCamCaptureTaskRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "device cam upload", description = "Token protected upload endpoints for cam devices")
@RestController
@RequestMapping("/device/cam")
@RequiredArgsConstructor
public class DeviceCamUploadController {

    private final DeviceCamService deviceCamService;

    @Operation(summary = "cam device uploads capture photo with task token")
    @PostMapping(value = "/capture-task/{taskId}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CommonResult<DeviceCamCaptureTaskRespVO> uploadCapturePhoto(
            @PathVariable String taskId,
            @RequestParam String token,
            @RequestPart("file") MultipartFile file) {
        return CommonResult.success(deviceCamService.uploadCapturePhotoByDevice(taskId, token, file));
    }

    @Operation(summary = "cam device uploads flow photo with device token")
    @PostMapping(value = "/flow-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CommonResult<Boolean> uploadFlowPhoto(
            @RequestParam String camChipId,
            @RequestParam String token,
            @RequestParam(required = false) Integer personCount,
            @RequestParam(required = false) Double confidence,
            @RequestParam(required = false) String detectTime,
            @RequestPart("file") MultipartFile file) {
        deviceCamService.uploadFlowPhotoByDevice(camChipId, token, personCount, confidence, detectTime, file);
        return CommonResult.success(true);
    }
}
