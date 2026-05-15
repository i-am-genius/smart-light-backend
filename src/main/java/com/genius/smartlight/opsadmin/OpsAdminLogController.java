package com.genius.smartlight.opsadmin;

import com.genius.smartlight.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/ops-admin/logs")
@RequiredArgsConstructor
public class OpsAdminLogController {

    private final OpsAdminLogService logService;

    @GetMapping("/tail")
    public ApiResponse<Map<String, Object>> tail(
            @RequestParam(defaultValue = "important") String type,
            @RequestParam(defaultValue = "200") int lines,
            @RequestParam(defaultValue = "ALL") String level,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "ALL") String module) {
        return ApiResponse.success(logService.tail(type, lines, level, keyword, module));
    }
}
