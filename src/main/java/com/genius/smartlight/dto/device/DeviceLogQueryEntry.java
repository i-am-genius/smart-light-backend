package com.genius.smartlight.dto.device;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceLogQueryEntry {
    private String chipId;
    private String timestamp;    // 后端补充的真实时间戳（Unix 毫秒字符串）
    private Long uptimeMs;       // ESP8266 启动后的毫秒数
    private String level;
    private String module;
    private String message;
}
