package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "cam 手动追踪控制请求")
public class DeviceCamTrackingControlReqVO {

    @NotBlank(message = "camChipId 不能为空")
    private String camChipId;

    @NotBlank(message = "targetChipId 不能为空")
    private String targetChipId;

    @NotNull(message = "targetIndex 不能为空")
    @Min(value = 1, message = "targetIndex 必须在 1 到 3 之间")
    @Max(value = 3, message = "targetIndex 必须在 1 到 3 之间")
    private Integer targetIndex;
}
