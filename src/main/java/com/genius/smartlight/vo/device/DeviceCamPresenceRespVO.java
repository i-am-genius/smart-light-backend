package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "cam ROI presence 缓存状态")
public class DeviceCamPresenceRespVO {
    private String camChipId;
    private String workStatus;
    private Boolean configured;
    private Integer personCount;
    private Double confidence;
    private List<DeviceCamPresenceReqVO.PresenceArea> areas = new ArrayList<>();
    private LocalDateTime updateTime;
}
