package com.genius.smartlight.vo.device;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "cam ROI 区域配置")
public class DeviceCamRoiItemVO {
    private Integer targetIndex;
    private String targetChipId;
    private String areaName;
    private Double x;
    private Double y;
    private Double w;
    private Double h;
}
