package com.genius.smartlight.controller.admin.device;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.device.DeviceOtaFirmwareService;
import com.genius.smartlight.vo.device.DeviceOtaFirmwareRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "OTA 固件管理")
@RestController
@RequestMapping("/admin/device/ota/firmware")
@RequiredArgsConstructor
public class DeviceOtaFirmwareController {

    private final DeviceOtaFirmwareService deviceOtaFirmwareService;

    @Operation(summary = "上传 OTA 固件")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CommonResult<DeviceOtaFirmwareRespVO> uploadFirmware(
            @RequestPart("file") MultipartFile file,
            @RequestParam String deviceType,
            @RequestParam String channel,
            @RequestParam String version,
            @RequestParam Integer versionCode,
            @RequestParam(required = false) String changelog,
            @RequestParam(required = false) String md5,
            @RequestHeader(value = "Host", required = false) String host,
            HttpServletRequest request
    ) {
        String requestHost = host != null && !host.isBlank() ? host : request.getServerName() + ":" + request.getServerPort();
        return CommonResult.success(deviceOtaFirmwareService.uploadFirmware(
                file,
                deviceType,
                channel,
                version,
                versionCode,
                changelog,
                md5,
                requestHost
        ));
    }

    @Operation(summary = "查询 OTA 固件历史版本")
    @GetMapping("/list")
    public CommonResult<List<DeviceOtaFirmwareRespVO>> listFirmware(
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String channel
    ) {
        return CommonResult.success(deviceOtaFirmwareService.listFirmware(deviceType, channel));
    }
}
