package com.genius.smartlight.vo.duration;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "停留时长响应")
@Data
public class DurationRespVO {

    @Schema(description = "停留时长记录ID", example = "1")
    private Long id;

    @Schema(description = "芯片唯一ID", example = "ABC123456")
    private String chipId;

    @Schema(description = "统计日期", example = "2026-04-14")
    private LocalDate statDate;

    @Schema(description = "停留时长，单位毫秒", example = "12000")
    private Long durationValue;

    @Schema(description = "创建时间", example = "2026-04-14T10:30:00")
    private LocalDateTime createTime;

    @Schema(description = "更新时间", example = "2026-04-14T11:00:00")
    private LocalDateTime updateTime;
}
