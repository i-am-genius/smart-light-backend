package com.genius.smartlight.vo.device;

import lombok.Data;

@Data
public class DeviceSliderStatusReqVO {
    private String chipId;
    private String taskId;
    private String status;
    private Double targetMm;
    private Long positionSteps;
    private Long uptimeMs;
}
