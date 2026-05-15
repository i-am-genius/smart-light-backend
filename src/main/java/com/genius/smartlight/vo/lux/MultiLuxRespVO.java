package com.genius.smartlight.vo.lux;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "多设备光照趋势响应")
@Data
public class MultiLuxRespVO {

    @Schema(description = "趋势图时间标签列表", example = "[\"10:00\", \"10:05\"]")
    private List<String> labels;

    @Schema(description = "多设备光照数据集")
    private List<Dataset> datasets;

    @Data
    @Schema(description = "单个设备光照数据集")
    public static class Dataset {

        @Schema(description = "芯片唯一ID或图例名称", example = "ABC123456")
        private String label;

        @Schema(description = "光照值序列，单位 lux", example = "[356.5, 360.0]")
        private List<Double> data;
    }
}
