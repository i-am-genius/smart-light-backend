package com.genius.smartlight.vo.device;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeviceCamGlobalTrackingControlReqVO {

    @NotBlank(message = "camChipId 不能为空")
    private String camChipId;
}
