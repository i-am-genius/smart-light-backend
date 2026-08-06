package com.genius.smartlight.vo.device;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DeviceLampClothStateRespVO {
    private String chipId;
    private String clothState;
    private LocalDateTime lastTakenAt;
    private Boolean tracking;
    private LocalDateTime updateTime;
}
