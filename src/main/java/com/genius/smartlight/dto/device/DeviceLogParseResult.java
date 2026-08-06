package com.genius.smartlight.dto.device;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Schema(description = "NDJSON 解析结果")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceLogParseResult {

    @Schema(description = "收到的总行数", example = "10")
    private int received;

    @Schema(description = "解析失败的行数", example = "2")
    private int invalid;

    @Schema(description = "解析成功的日志条目列表")
    private List<DeviceLogEntryDTO> entries;
}
