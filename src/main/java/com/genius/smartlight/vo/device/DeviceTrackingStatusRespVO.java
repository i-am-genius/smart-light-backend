package com.genius.smartlight.vo.device;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class DeviceTrackingStatusRespVO {
    private String chipId;
    private String role;
    private String trackingStatus;
    private String trackingMode;
    private String sessionId;
    private String camChipId;
    private String lampChipId;
    private Integer targetIndex;
    private List<String> targetChipIds;
    private Double confidence;
    private Long sequence;
    private String message;
    private LocalDateTime updateTime;
}
