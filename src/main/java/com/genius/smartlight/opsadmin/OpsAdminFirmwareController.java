package com.genius.smartlight.opsadmin;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.device.DeviceOtaFirmwareService;
import com.genius.smartlight.vo.device.DeviceOtaFirmwareRespVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ops-admin/firmware")
@RequiredArgsConstructor
public class OpsAdminFirmwareController {

    private final DeviceOtaFirmwareService firmwareService;

    @GetMapping("/list")
    public CommonResult<List<DeviceOtaFirmwareRespVO>> list(
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String channel) {
        return CommonResult.success(firmwareService.listFirmware(deviceType, channel));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CommonResult<DeviceOtaFirmwareRespVO> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam String deviceType,
            @RequestParam String channel,
            @RequestParam String version,
            @RequestParam Integer versionCode,
            @RequestParam(required = false) String changelog,
            @RequestParam(required = false) String md5,
            @RequestHeader(value = "Host", required = false) String host,
            jakarta.servlet.http.HttpServletRequest request) {
        String h = host != null && !host.isBlank() ? host : request.getServerName() + ":" + request.getServerPort();
        return CommonResult.success(firmwareService.uploadFirmware(file, deviceType, channel, version, versionCode, changelog, md5, h));
    }

    @PutMapping("/update/{id}")
    public CommonResult<DeviceOtaFirmwareRespVO> update(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        return CommonResult.success(firmwareService.updateFirmware(
                id,
                (String) body.get("version"),
                body.get("versionCode") != null ? ((Number) body.get("versionCode")).intValue() : null,
                (String) body.get("changelog"),
                (String) body.get("md5"),
                (String) body.get("fileUrl")
        ));
    }

    @DeleteMapping("/delete/{id}")
    public CommonResult<Boolean> delete(@PathVariable Long id) {
        firmwareService.deleteFirmware(id);
        return CommonResult.success(true);
    }

    @PostMapping("/enable/{id}")
    public CommonResult<DeviceOtaFirmwareRespVO> enable(@PathVariable Long id) {
        return CommonResult.success(firmwareService.enableFirmware(id));
    }

    @PostMapping("/disable/{id}")
    public CommonResult<DeviceOtaFirmwareRespVO> disable(@PathVariable Long id) {
        return CommonResult.success(firmwareService.disableFirmware(id));
    }
}
