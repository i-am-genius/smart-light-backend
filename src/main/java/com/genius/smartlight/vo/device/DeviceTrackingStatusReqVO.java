package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "cam/lamp 低频追踪状态上报")
public class DeviceTrackingStatusReqVO {
    @NotBlank(message = "chipId 不能为空")
    private String chipId;
    private String role;
    private String trackingStatus;
    private String camChipId;
    private String lampChipId;
    private Integer targetIndex;
    private Double confidence;
    private Long sequence;
    private String message;
}
