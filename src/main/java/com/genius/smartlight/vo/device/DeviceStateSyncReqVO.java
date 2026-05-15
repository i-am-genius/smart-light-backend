package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "设备状态同步请求。后端保存状态并向终端下发灯光状态")
@Data
public class DeviceStateSyncReqVO {

    @Schema(description = "目标亮度，范围 0-100", example = "80")
    private Integer brightness;

    @Schema(description = "目标色温，单位 K", example = "4500")
    private Integer temp;

    @Schema(description = "是否开启自动模式", example = "true")
    private Boolean autoMode;

    @Schema(description = "AI 推荐亮度，范围通常为 0-100", example = "75")
    private Integer recommendedBrightness;

    @Schema(description = "AI 推荐色温，单位 K", example = "5000")
    private Integer recommendedTemp;

    @Schema(description = "AI 识别出的面料类型", example = "cotton")
    private String fabric;

    @Schema(description = "AI 识别出的服装主色 RGB 值，格式为 R,G,B", example = "255,200,120")
    private String mainColorRgb;
}
