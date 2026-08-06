package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "cam 本地 ROI presence 上报")
public class DeviceCamPresenceReqVO {
    @NotBlank(message = "camChipId 不能为空")
    private String camChipId;
    private String workStatus;
    private Integer personCount;
    private Double confidence;
    private String detectTime;
    private List<PresenceArea> areas = new ArrayList<>();

    @Data
    public static class PresenceArea {
        private Integer targetIndex;
        private String targetChipId;
        private String areaName;
        private Boolean present;
        private Double confidence;
        private Double dwellSeconds;
        private String updateTime;
    }
}
