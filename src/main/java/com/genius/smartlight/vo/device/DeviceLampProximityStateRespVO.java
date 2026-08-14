package com.genius.smartlight.vo.device;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DeviceLampProximityStateRespVO {
    private String chipId;
    private Boolean nearby;
    private LocalDateTime updateTime;
}

