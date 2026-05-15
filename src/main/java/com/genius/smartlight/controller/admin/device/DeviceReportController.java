package com.genius.smartlight.controller.admin.device;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.device.DeviceReportService;
import com.genius.smartlight.vo.device.DeviceStateReportReqVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "设备状态上报", description = "单片机设备通过 HTTP 上报当前状态，后端保存后推送给浏览器端")
@RestController
@RequestMapping("/admin/device")
@RequiredArgsConstructor
public class DeviceReportController {

    private final DeviceReportService deviceReportService;

    @Operation(
            summary = "设备上报当前状态",
            description = "设备端调用 /admin/device/state-report 上报 chipId、deviceType、ip、brightness、temp、autoMode、recommendedBrightness、recommendedTemp、fabric、mainColorRgb、firmwareVersion 等字段"
    )
    @PostMapping("/state-report")
    public CommonResult<Boolean> stateReport(@Valid @RequestBody DeviceStateReportReqVO reqVO) {
        deviceReportService.reportState(reqVO);
        return CommonResult.success(true);
    }
}
