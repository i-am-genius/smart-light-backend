package com.genius.smartlight.vo.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "今日预计节能效果响应")
@Data
public class StrategyCompareRespVO {

    @Schema(description = "是否有可用于估算的灯具数据", example = "true")
    private boolean hasData;

    @Schema(description = "是否为估算值", example = "true")
    private boolean estimated = true;

    @Schema(description = "今日预计节能率（百分比）", example = "42.5")
    private Double todaySavingRatePercent;

    @Schema(description = "传统照明基准能耗（kWh）", example = "0.84")
    private Double baselineEnergyKwh;

    @Schema(description = "智能照明预计能耗（kWh）", example = "0.48")
    private Double smartEnergyKwh;

    @Schema(description = "今日预计节省电量（kWh）", example = "0.36")
    private Double savedEnergyKwh;

    @Schema(description = "参与估算的灯具数量", example = "3")
    private Integer lampCount;

    @Schema(description = "处于自动调光模式的灯具数量", example = "2")
    private Integer autoDimmingDeviceCount;

    @Schema(description = "当前有效平均亮度（百分比）", example = "57.5")
    private Double averageBrightnessPercent;

    @Schema(description = "相对全亮的平均亮度下降（百分比）", example = "42.5")
    private Double averageBrightnessReductionPercent;

    @Schema(description = "亮度数据覆盖率（百分比）", example = "100")
    private Integer dataCoveragePercent;

    @Schema(description = "默认单灯额定功率（W）", example = "20")
    private Integer ratedPowerWatts;

    @Schema(description = "每日估算营业时长（小时）", example = "14")
    private Integer operatingHours;

    @Schema(description = "计算依据说明")
    private String calculationBasis;

    @Schema(description = "无数据时的降级提示")
    private String emptyReason;
}
