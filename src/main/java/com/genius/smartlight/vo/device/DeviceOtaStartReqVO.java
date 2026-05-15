package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "OTA update start request")
@Data
public class DeviceOtaStartReqVO {

    @Schema(description = "Optional firmware id. If absent, latest enabled firmware in current channel is used.")
    private Long firmwareId;

    @Schema(description = "Target firmware channel: stable or test. If absent, current device channel is used.")
    private String channel;
}
