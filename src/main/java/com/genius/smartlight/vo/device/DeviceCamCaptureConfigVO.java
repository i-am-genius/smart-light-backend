package com.genius.smartlight.vo.device;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "cam 拍摄对位配置；不包含 ROI 区域语义")
public class DeviceCamCaptureConfigVO {
    private String camChipId;

    @Schema(description = "实际连接 Nano 滑轨电机的 Lamp chipId")
    private String sliderLampChipId;

    @Schema(description = "固定三个拍摄目标，仅用于拍照/滑轨对位")
    private List<DeviceCamCaptureTargetVO> targets = new ArrayList<>();

    @Schema(description = "三个拍摄目标及滑轨控制灯是否已配置")
    private Boolean configured;
}
