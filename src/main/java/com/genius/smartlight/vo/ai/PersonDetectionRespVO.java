package com.genius.smartlight.vo.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class PersonDetectionRespVO {

    private Double x1;

    private Double y1;

    private Double x2;

    private Double y2;

    private Double confidence;

    @JsonProperty("class_id")
    private Integer classId;
}
