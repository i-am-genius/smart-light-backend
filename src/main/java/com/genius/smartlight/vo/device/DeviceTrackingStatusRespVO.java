package com.genius.smartlight.vo.device;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DeviceTrackingStatusRespVO {
    private String chipId;
    private String role;
    private String trackingStatus;
    private String camChipId;
    private String lampChipId;
    private Integer targetIndex;
    private Double confidence;
    private Long sequence;
    private String message;
    private LocalDateTime updateTime;
}
