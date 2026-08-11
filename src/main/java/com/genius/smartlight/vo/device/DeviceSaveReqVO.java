package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "设备新增/修改请求。用于后台维护设备基础信息和灯光状态")
@Data
public class DeviceSaveReqVO {

    @Schema(description = "芯片唯一ID，设备上报、WebSocket 匹配和控制指令均使用该字段", example = "ABC123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "芯片ID不能为空")
    private String chipId;

    @Schema(description = "设备类型。lamp 表示普通灯控设备，cam 表示独立摄像头云台设备，camlamp 表示带摄像头/云台的灯控设备", example = "lamp", allowableValues = {"lamp", "cam", "camlamp"}, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "设备类型不能为空")
    private String deviceType;

    @Schema(description = "店内编号，用于页面排序或人工识别", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "店内编号不能为空")
    private String deviceNo;

    @Schema(description = "设备显示名称", example = "橱窗灯", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "显示名称不能为空")
    private String displayName;

    @Schema(description = "设备局域网 IP 地址", example = "192.168.1.10")
    private String ip;

    @Schema(description = "当前亮度，范围 0-100", example = "80", minimum = "0", maximum = "100")
    @Min(value = 0, message = "亮度不能小于0")
    @Max(value = 100, message = "亮度不能大于100")
    private Integer brightness;

    @Schema(description = "当前色温，单位 K", example = "4500")
    private Integer temp;

    @Schema(description = "是否开启自动模式。true 表示设备根据策略自动调节", example = "true")
    private Boolean autoMode;

    @Schema(description = "是否根据最新服装识别坐标调整照射位置；false 使用默认服装预设", example = "false")
    private Boolean garmentAimEnabled;

    @Schema(description = "AI 推荐亮度，范围通常为 0-100", example = "75")
    private Integer recommendedBrightness;

    @Schema(description = "AI 推荐色温，单位 K", example = "5000")
    private Integer recommendedTemp;

    @Schema(description = "AI 识别出的面料类型", example = "cotton")
    private String fabric;

    @Schema(description = "AI 识别出的服装主色 RGB 值，格式为 R,G,B", example = "255,200,120")
    private String mainColorRgb;
}
