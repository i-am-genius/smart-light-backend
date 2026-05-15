package com.genius.smartlight.opsadmin;

import com.genius.smartlight.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/ops-admin/system")
@RequiredArgsConstructor
public class OpsAdminSystemController {

    private final OpsAdminSystemStatusService statusService;

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        try {
            return ApiResponse.success(statusService.collect());
        } catch (Exception e) {
            log.error("[ops-admin] Failed to collect system status", e);
            return ApiResponse.fail("系统状态采集失败: " + e.getMessage());
        }
    }
}
