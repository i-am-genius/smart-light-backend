package com.genius.smartlight.vo.personflow;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "人流趋势数据项")
@Data
public class PersonFlowTrendItemVO {

    @Schema(description = "时间标签", example = "2026-05-25 10:00")
    private String time;

    @Schema(description = "该时段平均画面人数，用于趋势图显示", example = "10.0")
    private Double personCount;

    @Schema(description = "该时段累计检测人次 SUM(person_count)", example = "20")
    private Integer totalPersonCount;

    @Schema(description = "该时段平均画面人数 AVG(person_count)", example = "10.0")
    private Double avgPersonCount;

    @Schema(description = "该时段峰值人数 MAX(person_count)", example = "13")
    private Integer maxPersonCount;

    @Schema(description = "该时段检测次数", example = "2")
    private Integer detectionCount;

    @Schema(description = "该时段平均置信度", example = "0.86")
    private Double avgConfidence;

    @Schema(description = "该时段平均处理时间，单位毫秒", example = "180.5")
    private Double avgProcessingTime;
}
