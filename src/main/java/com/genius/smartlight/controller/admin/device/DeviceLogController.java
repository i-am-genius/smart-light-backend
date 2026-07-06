package com.genius.smartlight.controller.admin.device;

import com.genius.smartlight.config.DeviceLogProperties;
import com.genius.smartlight.dto.device.DeviceLogEntryDTO;
import com.genius.smartlight.dto.device.DeviceLogParseResult;
import com.genius.smartlight.service.device.DeviceLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "设备日志", description = "设备端通过 NDJSON 格式批量上传运行日志")
@RestController
@RequestMapping("/admin/device")
@RequiredArgsConstructor
public class DeviceLogController {

    private final DeviceLogService deviceLogService;
    private final DeviceLogProperties properties;

    @Operation(
            summary = "批量上传设备日志",
            description = "设备端以 NDJSON 格式上传日志，每行一个 JSON 对象。需要在 X-Upload-Secret 头或 query 参数 uploadSecret 中携带正确的上传密钥"
    )
    @PostMapping("/logs/batch")
    public ResponseEntity<?> uploadLogsBatch(
            @Parameter(description = "设备芯片 ID") @RequestParam String chipId,
            @Parameter(description = "上传时设备的 uptimeMs") @RequestParam long uploadUptimeMs,
            @Parameter(description = "上传密钥，可通过 X-Upload-Secret 头或此参数传递") @RequestParam(required = false) String uploadSecret,
            @RequestHeader(value = "X-Upload-Secret", required = false) String uploadSecretHeader,
            @RequestBody String body
    ) {
        // 验证上传密钥：优先使用 header，其次 query param
        String secret = uploadSecretHeader != null ? uploadSecretHeader : uploadSecret;
        if (secret == null || !secret.equals(properties.getUploadSecret())) {
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "msg", "上传密钥无效"));
        }

        // 解析 NDJSON
        DeviceLogParseResult result = deviceLogService.parseNdjson(body);

        // 写入日志文件（NDJSON 格式，按日期分文件）
        if (!result.getEntries().isEmpty()) {
            deviceLogService.writeLogs(chipId, uploadUptimeMs, result.getEntries());
        }

        return ResponseEntity.ok(Map.of(
                "code", 0,
                "msg", "success",
                "data", Map.of(
                        "received", result.getReceived(),
                        "invalid", result.getInvalid(),
                        "saved", result.getEntries().size()
                )
        ));
    }

    @Operation(summary = "查询设备日志", description = "查询指定设备的日志记录")
    @GetMapping("/logs/query")
    public ResponseEntity<?> queryLogs(
            @Parameter(description = "设备芯片 ID") @RequestParam String chipId,
            @Parameter(description = "最大返回条数") @RequestParam(defaultValue = "100") int limit,
            @Parameter(description = "排序方式: asc/desc") @RequestParam(defaultValue = "desc") String order
    ) {
        List<DeviceLogEntryDTO> logs = deviceLogService.queryLogs(chipId, limit, order);
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "msg", "success",
                "data", logs
        ));
    }

    @Operation(summary = "列出有日志的设备", description = "返回所有上传过日志的设备 ID 列表")
    @GetMapping("/logs/devices")
    public ResponseEntity<?> listDevices() {
        List<String> devices = deviceLogService.listDevices();
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "msg", "success",
                "data", devices
        ));
    }
}
