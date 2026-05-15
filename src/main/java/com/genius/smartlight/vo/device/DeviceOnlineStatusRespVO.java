package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "设备在线状态响应")
@Data
public class DeviceOnlineStatusRespVO {

    @Schema(description = "芯片唯一ID", example = "ABC123456")
    private String chipId;

    @Schema(description = "设备局域网 IP 地址", example = "192.168.1.10")
    private String ip;

    @Schema(description = "是否在线。true 表示设备 WebSocket 当前连接可用", example = "true")
    private Boolean online;

    @Schema(description = "最近一次心跳或注册时间戳，单位毫秒", example = "1713062400000")
    private Long lastSeen;
}
