package com.genius.smartlight.vo.duration;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "设备停留时长汇总响应")
@Data
public class DurationDeviceSummaryRespVO {

    @Schema(description = "芯片唯一ID", example = "ABC123456")
    private String chipId;

    @Schema(description = "总停留时长，单位毫秒", example = "864000")
    private Long totalDuration;
}
