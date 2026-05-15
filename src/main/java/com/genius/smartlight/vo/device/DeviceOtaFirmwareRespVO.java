package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Uploaded OTA firmware response")
@Data
public class DeviceOtaFirmwareRespVO {

    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @tools.jackson.databind.annotation.JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)
    private Long id;

    private String deviceType;

    private String channel;

    private String version;

    private Integer versionCode;

    private String fileUrl;

    private String md5;

    private String changelog;

    private Boolean enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
