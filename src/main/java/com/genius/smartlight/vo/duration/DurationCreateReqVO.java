package com.genius.smartlight.vo.duration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Schema(description = "停留时长新增请求。用于新增或累计指定设备在某天的停留时长")
@Data
public class DurationCreateReqVO {

    @Schema(description = "芯片唯一ID", example = "ABC123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "芯片ID不能为空")
    private String chipId;

    @Schema(description = "统计日期，可选；不传则服务端使用当天日期", example = "2026-04-14")
    private LocalDate statDate;

    @Schema(description = "停留时长，单位毫秒", example = "12000", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "停留时长不能为空")
    private Long durationValue;
}
