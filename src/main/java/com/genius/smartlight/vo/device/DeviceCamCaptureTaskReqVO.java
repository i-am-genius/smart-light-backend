package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "创建 cam 服装拍摄任务")
public class DeviceCamCaptureTaskReqVO {
    @NotBlank(message = "camChipId 不能为空")
    private String camChipId;
    private String targetChipId;
    private Integer targetIndex;
}
