package com.genius.smartlight.dto.device;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "单条设备日志条目")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceLogEntryDTO {

    @Schema(description = "设备 uptime (ms)", example = "252215000")
    private Long uptimeMs;

    @Schema(description = "日志序号", example = "1001")
    private Integer seq;

    @Schema(description = "后端补充的真实时间戳（Unix 毫秒）", example = "1783317300000")
    private Long ts;

    @Schema(description = "日志级别", example = "INFO", allowableValues = {"DEBUG", "INFO", "WARN", "ERROR"})
    private String level;

    @Schema(description = "模块名称", example = "sensor")
    private String module;

    @Schema(description = "日志内容", example = "BH1750 read 120 lux")
    private String msg;
}
