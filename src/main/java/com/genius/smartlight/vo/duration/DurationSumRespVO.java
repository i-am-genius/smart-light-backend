package com.genius.smartlight.vo.duration;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Schema(description = "停留时长汇总响应")
@Data
public class DurationSumRespVO {

    @Schema(description = "芯片唯一ID", example = "ABC123456")
    private String chipId;

    @Schema(description = "开始日期", example = "2026-04-01")
    private LocalDate startDate;

    @Schema(description = "结束日期", example = "2026-04-14")
    private LocalDate endDate;

    @Schema(description = "总停留时长，单位毫秒", example = "864000")
    private Long totalDuration;
}
