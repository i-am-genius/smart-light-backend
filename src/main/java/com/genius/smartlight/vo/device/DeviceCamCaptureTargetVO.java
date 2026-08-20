package com.genius.smartlight.vo.device;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "cam 拍摄目标与滑轨对位配置")
public class DeviceCamCaptureTargetVO {
    @Schema(description = "拍摄目标序号，固定为 1~3")
    private Integer index;

    @Schema(description = "该拍摄目标对应的 Lamp chipId")
    private String lampChipId;

    @Schema(description = "拍摄该目标时的滑轨位置，单位 mm")
    private Double sliderMm;

    @Schema(description = "从 0 mm 移动到该位置的 slow/normal/fast 实测时间，单位秒")
    private DeviceCamSliderMoveTimeVO moveTimes = new DeviceCamSliderMoveTimeVO();
}
