package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Camera PTZ control request for cam devices")
public class DeviceCamPtzReqVO {

    @NotBlank(message = "chipId cannot be blank")
    @Schema(description = "Device chip ID", example = "cam001", requiredMode = Schema.RequiredMode.REQUIRED)
    private String chipId;

    @Schema(description = "PTZ axis: yaw, pitch, roll, or all", example = "yaw")
    private String axis;

    @Schema(description = "Direction: left, right, up, down, cw, ccw, or center", example = "left")
    private String direction;

    @Schema(description = "Step angle, 1-30 degrees", example = "5")
    private Integer step;

    @Schema(description = "Absolute yaw angle")
    private Float yaw;

    @Schema(description = "Absolute pitch angle")
    private Float pitch;

    @Schema(description = "Absolute roll angle")
    private Float roll;
}
