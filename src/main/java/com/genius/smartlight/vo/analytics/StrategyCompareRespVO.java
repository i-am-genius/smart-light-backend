package com.genius.smartlight.vo.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "固定策略与智能策略对比响应")
@Data
public class StrategyCompareRespVO {

    @Schema(description = "时间标签序列", example = "[\"05-08 10:30\", \"05-08 11:00\"]")
    private List<String> labels = new ArrayList<>();

    @Schema(description = "固定策略序列", example = "[70, 70]")
    private List<Integer> fixedSeries = new ArrayList<>();

    @Schema(description = "智能策略序列", example = "[62, 68]")
    private List<Integer> smartSeries = new ArrayList<>();
}
