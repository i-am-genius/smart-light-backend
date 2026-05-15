package com.genius.smartlight.vo.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "人流检测响应。包含人数、置信度、处理耗时和标注图 Base64")
@Data
public class PersonDetectRespVO {

    @Schema(description = "检测到的人数", example = "3")
    private Integer count;

    @Schema(description = "检测置信度，范围通常为 0-1", example = "0.88")
    private Double confidence;

    @Schema(description = "检测时间戳", example = "2026-04-14T10:30:00")
    private String timestamp;

    @Schema(description = "AI 服务处理耗时，单位秒", example = "0.15")
    @JsonProperty("processing_time")
    private Double processingTime;

    @Schema(description = "标注后图片的 Base64 编码。前端可兼容带 data:image 前缀或纯 Base64 内容", example = "iVBORw0KGgoAAAANSUhEUg...")
    @JsonProperty("annotated_image_base64")
    private String annotatedImageBase64;
}
