package com.genius.smartlight.vo.device;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DeviceCamCaptureTaskRespVO {
    private String taskId;
    private String camChipId;
    private String targetChipId;
    private Integer targetIndex;
    private String status;
    private String message;
    private String imageName;
    private String photoUrl;
    private LocalDateTime createTime;
}
