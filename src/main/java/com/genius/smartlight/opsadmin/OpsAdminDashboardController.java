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
@RequestMapping("/ops-admin/dashboard")
@RequiredArgsConstructor
public class OpsAdminDashboardController {

    private final OpsAdminDashboardService dashboardService;

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        try {
            return ApiResponse.success(dashboardService.summary());
        } catch (Exception e) {
            log.error("[ops-admin] Failed to get dashboard summary", e);
            return ApiResponse.fail("获取控制台概览失败: " + e.getMessage());
        }
    }
}
