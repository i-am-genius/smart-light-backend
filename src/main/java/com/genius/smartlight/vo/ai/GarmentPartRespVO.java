package com.genius.smartlight.vo.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "单件服装识别结果")
public class GarmentPartRespVO {

    private String position;
    private String category;
    private Double categoryConfidence;
    private String fabric;
    private Double fabricConfidence;
    private String mainColorRgb;
    private Integer maskArea;
    private Integer x;
    private Integer y;
    private Integer w;
    private Integer h;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String colorSamplePngBase64;
}
