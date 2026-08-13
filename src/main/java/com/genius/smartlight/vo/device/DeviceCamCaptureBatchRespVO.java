package com.genius.smartlight.vo.device;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class DeviceCamCaptureBatchRespVO {
    private String batchId;
    private String camChipId;
    private String status;
    private String message;
    private List<DeviceCamCaptureTaskRespVO> tasks = new ArrayList<>();
    private LocalDateTime createTime;
}
