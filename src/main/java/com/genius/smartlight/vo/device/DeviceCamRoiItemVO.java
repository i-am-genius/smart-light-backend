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
    @Schema(description = "该区域 SG90 服装拍摄 Pan 预置角度（0~180）", example = "90")
    private Double garmentCapturePan;
    @Schema(description = "该区域 SG90 服装拍摄 Tilt 预置角度（0~180）", example = "90")
    private Double garmentCaptureTilt;
    @Schema(description = "该区域 SG90 人物拍摄 Pan 预置角度（0~180）", example = "90")
    private Double personCapturePan;
    @Schema(description = "该区域 SG90 人物拍摄 Tilt 预置角度（0~180）", example = "90")
    private Double personCaptureTilt;
    @Schema(description = "灯具在滑轨上的物理碰撞中心，单位 mm")
    private Double collisionCenterMm;
    @Schema(description = "碰撞中心两侧避让距离，单位 mm")
    private Double collisionClearanceMm;
    @Schema(description = "灯具移动到 Pan=0/Tilt=0 的最坏实测时间，单位秒")
    private Double collisionParkTimeSeconds;
}
