package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "设备云台/机械臂控制请求。type=arm_joystick 摇杆连续控制；type=arm_stop 停止；type=arm_position 精确位置；type=arm_speed 仅切速度；type=arm 仅方向动作。action 为空时兼容旧字段 direction")
@Data
public class DeviceArmControlReqVO {

    @Schema(description = "消息类型：arm_joystick/arm_stop/arm_position/arm_speed/arm。默认为 arm（兼容旧客户端）", example = "arm_joystick", allowableValues = {"arm_joystick", "arm_stop", "arm_position", "arm_speed", "arm"})
    private String type;

    @Schema(description = "云台/机械臂动作，例如 up、down、left、right、center、home、stop、slider_position", example = "left")
    private String action;

    @Schema(description = "旧字段：云台转动方向。action 为空时使用该字段", example = "left")
    private String direction;

    @Schema(description = "动作速度：slow、normal、fast。仅 type=arm_speed 或旧协议时使用", example = "normal", allowableValues = {"slow", "normal", "fast"})
    private String speed;

    @Schema(description = "滑轨位置，单位 mm，仅 action=slider_position 时使用", example = "120")
    private Integer position;

    // === 摇杆连续控制字段 (type=arm_joystick) ===
    @Schema(description = "摇杆 X 轴：-1~1，负=左，正=右", example = "0")
    private Float x;

    @Schema(description = "摇杆 Y 轴：-1~1，负=下，正=上", example = "1")
    private Float y;

    @Schema(description = "摇杆指令有效期 ms，前端续期间隔 250ms", example = "500")
    private Integer durationMs;

    // === 精确位置控制字段 (type=arm_position) ===
    @Schema(description = "水平角度，单位 °", example = "10")
    private Float pan;

    @Schema(description = "俯仰角度，单位 °", example = "-5")
    private Float tilt;

    @Schema(description = "滑轨位置，单位 mm", example = "80")
    private Float slider;
}
