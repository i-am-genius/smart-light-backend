package com.genius.smartlight.controller.admin.device;

import com.genius.smartlight.common.CommonResult;
import com.genius.smartlight.service.device.DeviceOnlineService;
import com.genius.smartlight.vo.device.DeviceOnlineStatusRespVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "设备在线状态", description = "查询设备 WebSocket 在线状态和最近心跳时间")
@RestController
@RequestMapping("/admin/device")
@RequiredArgsConstructor
public class DeviceOnlineController {

    private final DeviceOnlineService deviceOnlineService;

    @Operation(summary = "查询单个设备在线状态", description = "根据 chipId 查询设备是否在线、最近心跳时间和 IP")
    @GetMapping("/online-status/{chipId}")
    public CommonResult<DeviceOnlineStatusRespVO> getOnlineStatus(
            @Parameter(description = "芯片唯一ID", example = "ABC123456")
            @PathVariable String chipId) {
        return CommonResult.success(deviceOnlineService.getOnlineStatus(chipId));
    }

    @Operation(summary = "查询在线设备列表", description = "返回当前在线设备列表，包含 chipId、ip、online、lastSeen")
    @GetMapping("/online-list")
    public CommonResult<List<DeviceOnlineStatusRespVO>> getOnlineStatusList() {
        return CommonResult.success(deviceOnlineService.getOnlineStatusList());
    }
}
