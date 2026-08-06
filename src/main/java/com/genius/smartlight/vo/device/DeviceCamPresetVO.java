package com.genius.smartlight.vo.device;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "摄像头拍照或追踪预设")
public class DeviceCamPresetVO {
    @Schema(description = "Pan 水平角度，单位度", example = "0")
    private Double pan;

    @Schema(description = "Tilt 俯仰角度，单位度", example = "0")
    private Double tilt;

    @Schema(description = "Slider 滑轨位置，单位 mm", example = "320")
    private Double slider;

    @JsonSetter("yaw")
    public void readLegacyYaw(Double yaw) {
        if (pan == null && yaw != null && Double.isFinite(yaw)) {
            pan = yaw - 90D;
        }
    }

    @JsonSetter("pitch")
    public void readLegacyPitch(Double pitch) {
        if (tilt == null) {
            tilt = pitch;
        }
    }

    @JsonSetter("roll")
    public void ignoreLegacyRoll(Double ignored) {
        // Rotation degrees cannot be converted safely to slider millimetres.
    }
}
