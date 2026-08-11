package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "创建 cam 服装拍摄任务；上传给 AI 的是完整原图")
public class DeviceCamCaptureTaskReqVO {
    @NotBlank(message = "camChipId 不能为空")
    private String camChipId;

    @Schema(description = "目标 Lamp；服装标定时用于自动解析 Camera 对位拍摄预设")
    private String targetChipId;

    @Schema(description = "兼容旧的手动区域拍摄；省略时根据目标 Lamp 自动解析")
    private Integer targetIndex;
}
