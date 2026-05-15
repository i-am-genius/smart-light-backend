package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "设备云台/机械臂控制请求。action 优先，action 为空时兼容旧字段 direction")
@Data
public class DeviceArmControlReqVO {

    @Schema(description = "云台/机械臂动作，例如 up、down、left、right、center、home、stop、slider_position", example = "left")
    private String action;

    @Schema(description = "旧字段：云台转动方向。action 为空时使用该字段", example = "left")
    private String direction;

    @Schema(description = "动作速度：slow、normal、fast", example = "normal", allowableValues = {"slow", "normal", "fast"})
    private String speed;

    @Schema(description = "滑轨位置，单位 mm，仅 action=slider_position 时使用", example = "120")
    private Integer position;
}
