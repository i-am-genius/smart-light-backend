package com.genius.smartlight.vo.lighteffect;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "Light effect state response")
public class LightEffectStateRespVO {

    @Schema(description = "Effect name", example = "wave")
    private String effect;

    @Schema(description = "Whether the effect is enabled", example = "true")
    private Boolean enabled;

    @Schema(description = "Minimum wave color temperature in Kelvin", example = "2700")
    private Integer minTemp;

    @Schema(description = "Maximum wave color temperature in Kelvin", example = "6500")
    private Integer maxTemp;

    @Schema(description = "Base color temperature in Kelvin", example = "3800")
    private Integer baseTemp;

    @Schema(description = "Wave color-temperature range", example = "500")
    private Integer range;

    @Schema(description = "Wave color-temperature amplitude", example = "1900")
    private Integer amplitude;

    @Schema(description = "Wave speed multiplier. Larger means faster.", example = "1")
    private Double speed;

    @Schema(description = "Brightness percentage", example = "74")
    private Integer brightness;

    @Schema(description = "Current wave phase index", example = "0")
    private Double phaseIndex;

    @Schema(description = "Phase gap between adjacent devices", example = "0.8")
    private Double phaseGap;

    @Schema(description = "Target scope", example = "all")
    private String selectedScope;

    @Schema(description = "Last update time")
    private LocalDateTime updateTime;
}
