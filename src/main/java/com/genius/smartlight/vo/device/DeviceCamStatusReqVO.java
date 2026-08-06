package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "cam 工作状态上报")
public class DeviceCamStatusReqVO {
    @NotBlank(message = "camChipId 不能为空")
    private String camChipId;
    private String workStatus;
    private Integer activeTargetIndex;
    private String activeTargetChipId;
    private String message;
}
