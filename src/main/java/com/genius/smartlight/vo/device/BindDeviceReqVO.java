package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "绑定设备请求参数")
public class BindDeviceReqVO {

    @Schema(description = "芯片ID", example = "ABC123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "芯片ID不能为空")
    private String chipId;

    @Schema(description = "设备显示名称", example = "入口射灯")
    private String displayName;
}