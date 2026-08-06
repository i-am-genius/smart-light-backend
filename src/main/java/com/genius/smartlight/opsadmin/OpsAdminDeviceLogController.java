package com.genius.smartlight.opsadmin;

import com.genius.smartlight.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ops-admin/device-logs")
@RequiredArgsConstructor
public class OpsAdminDeviceLogController {

    private final OpsAdminDeviceLogService deviceLogService;

    @GetMapping("/devices")
    public ApiResponse<List<String>> listDevices() {
        return ApiResponse.success(deviceLogService.listDevices());
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> queryLogs(
            @RequestParam(required = false) String chipId,
            @RequestParam(required = false, defaultValue = "ALL") String level,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "") String date,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        OpsAdminDeviceLogService.DeviceLogQueryResult result =
                deviceLogService.queryLogs(chipId, level, keyword, date, offset, limit);
        return ApiResponse.success(Map.of(
                "entries", result.getEntries(),
                "total", result.getTotal(),
                "hasMore", result.isHasMore()
        ));
    }
}
