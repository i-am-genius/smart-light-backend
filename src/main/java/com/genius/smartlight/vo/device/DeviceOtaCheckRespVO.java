package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "OTA update check response")
@Data
public class DeviceOtaCheckRespVO {

    private String chipId;

    private String deviceType;

    private String channel;

    private String currentVersion;

    private Integer currentVersionCode;

    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @tools.jackson.databind.annotation.JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)
    private Long firmwareId;

    private String latestVersion;

    private Integer latestVersionCode;

    private String fileUrl;

    private String md5;

    private String changelog;

    private Boolean hasUpdate;

    private String otaStatus;
}
