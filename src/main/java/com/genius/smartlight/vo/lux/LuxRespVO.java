package com.genius.smartlight.vo.lux;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "光照记录响应")
@Data
public class LuxRespVO {

    @Schema(description = "主键ID", example = "1")
    private Long id;

    @Schema(description = "芯片唯一ID", example = "ABC123456")
    private String chipId;

    @Schema(description = "光照值，单位 lux", example = "356.5")
    private Double luxValue;

    @Schema(description = "采集时间", example = "2026-04-14T10:30:00")
    private LocalDateTime collectTime;

    @Schema(description = "创建时间", example = "2026-04-14T10:31:00")
    private LocalDateTime createTime;
}
