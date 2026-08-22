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

    @Schema(description = "该目标 SG90 服装拍摄 Pan 角度（0~180）", example = "90")
    private Double garmentCapturePan;

    @Schema(description = "该目标 SG90 服装拍摄 Tilt 角度（0~180）", example = "90")
    private Double garmentCaptureTilt;

    @Schema(description = "该目标 SG90 人物拍摄 Pan 角度（0~180）", example = "90")
    private Double personCapturePan;

    @Schema(description = "该目标 SG90 人物拍摄 Tilt 角度（0~180）", example = "90")
    private Double personCaptureTilt;

    @Schema(description = "该灯移动到 Pan=0/Tilt=0 的最坏时间，单位秒")
    private Double collisionParkTimeSeconds;

    @Schema(description = "从 0 mm 移动到该位置的 slow/normal/fast 实测时间，单位秒")
    private DeviceCamSliderMoveTimeVO moveTimes = new DeviceCamSliderMoveTimeVO();
}
