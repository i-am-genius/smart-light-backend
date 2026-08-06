package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Camera aim target request for cam devices")
public class DeviceCamAimTargetReqVO {

    @NotBlank(message = "camChipId cannot be blank")
    @Schema(description = "Camera device chip ID", example = "CAM-1", requiredMode = Schema.RequiredMode.REQUIRED)
    private String camChipId;

    @Schema(description = "Optional target lamp/camlamp chip ID", example = "LAMP-CCC267")
    private String targetChipId;

    @Schema(description = "Target index, 1-3", example = "1")
    private Integer targetIndex;
}
