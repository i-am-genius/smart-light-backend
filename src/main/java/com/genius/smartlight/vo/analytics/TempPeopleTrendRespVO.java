package com.genius.smartlight.vo.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "温度与人流趋势响应")
@Data
public class TempPeopleTrendRespVO {

    @Schema(description = "时间标签序列", example = "[\"05-08 10:30\", \"05-08 11:00\"]")
    private List<String> labels = new ArrayList<>();

    @Schema(description = "温度序列，单位摄氏度", example = "[26.5, 27.1]")
    private List<Double> tempSeries = new ArrayList<>();

    @Schema(description = "人流序列，来自 person_flow_record 统计。暂无真实人流数据时返回 0", example = "[3, 0]")
    private List<Integer> peopleSeries = new ArrayList<>();
}
