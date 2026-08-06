package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "lamp ToF 服装取下状态上报")
public class DeviceLampClothStateReqVO {
    @NotBlank(message = "chipId 不能为空")
    private String chipId;
    private String clothState;
    private String lastTakenAt;
    private Boolean tracking;
}
