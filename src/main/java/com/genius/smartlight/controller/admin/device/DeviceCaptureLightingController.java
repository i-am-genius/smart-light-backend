package com.genius.smartlight.controller.admin.device;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.device.CaptureLightingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/device/lamp/{lampChipId}/capture-lighting")
@RequiredArgsConstructor
public class DeviceCaptureLightingController {

    private final CaptureLightingService captureLightingService;

    @PostMapping("/start")
    public CommonResult<Boolean> start(@PathVariable String lampChipId) {
        captureLightingService.startStandard(lampChipId);
        sleepSettleTime();
        return CommonResult.success(true);
    }

    @PostMapping("/stop")
    public CommonResult<Boolean> stop(@PathVariable String lampChipId) {
        captureLightingService.stop(lampChipId);
        return CommonResult.success(true);
    }

    private void sleepSettleTime() {
        long settleMs = captureLightingService.getSettleMs();
        if (settleMs <= 0) {
            return;
        }
        try {
            Thread.sleep(settleMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
