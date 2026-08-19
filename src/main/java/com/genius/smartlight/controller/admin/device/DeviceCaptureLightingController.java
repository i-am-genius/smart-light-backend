package com.genius.smartlight.controller.admin.device;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.device.CaptureLightingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/device/lamp/{lampChipId}/capture-lighting")
@RequiredArgsConstructor
public class DeviceCaptureLightingController {

    private final CaptureLightingService captureLightingService;

    @PostMapping("/start")
    public CommonResult<String> start(
            @PathVariable String lampChipId,
            @RequestParam(required = false) String sessionId) {
        String normalizedSessionId = captureLightingService.startStandard(lampChipId, sessionId);
        sleepSettleTime();
        return CommonResult.success(normalizedSessionId);
    }

    @PostMapping("/stop")
    public CommonResult<Boolean> stop(
            @PathVariable String lampChipId,
            @RequestParam String sessionId) {
        captureLightingService.stop(lampChipId, sessionId);
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
