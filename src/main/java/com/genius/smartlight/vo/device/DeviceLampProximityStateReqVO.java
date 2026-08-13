package com.genius.smartlight.vo.device;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeviceLampProximityStateReqVO {
    @NotBlank(message = "chipId 不能为空")
    private String chipId;
    private Boolean nearby;
}

