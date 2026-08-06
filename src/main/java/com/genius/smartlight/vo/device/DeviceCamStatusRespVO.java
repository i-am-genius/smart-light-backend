package com.genius.smartlight.vo.device;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DeviceCamStatusRespVO {
    private String camChipId;
    private String workStatus;
    private Integer activeTargetIndex;
    private String activeTargetChipId;
    private String message;
    private LocalDateTime updateTime;
}
