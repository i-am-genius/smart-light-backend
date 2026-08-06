package com.genius.smartlight.vo.device;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "cam ROI 与预设位配置")
public class DeviceCamRoiConfigVO {
    private String camChipId;
    private List<DeviceCamRoiItemVO> rois = new ArrayList<>();
    private Map<String, DeviceCamPresetVO> capturePresets = new LinkedHashMap<>();
    private Map<String, DeviceCamPresetVO> trackingPresets = new LinkedHashMap<>();
    private Boolean configured;
}
