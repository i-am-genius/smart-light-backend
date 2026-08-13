package com.genius.smartlight.vo.device;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "从 0 mm 移动到对应滑轨预设位置的三档实测时间，单位秒")
public class DeviceCamSliderMoveTimeVO {

    private Double slow;
    private Double normal;
    private Double fast;

    public Double timeFor(String speedMode) {
        return switch (speedMode == null ? "normal" : speedMode) {
            case "slow" -> slow;
            case "fast" -> fast;
            default -> normal;
        };
    }
}
