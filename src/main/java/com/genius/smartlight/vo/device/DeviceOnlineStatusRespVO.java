package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Device online status response")
@Data
public class DeviceOnlineStatusRespVO {

    @Schema(description = "Unique device chip id", example = "ABC123456")
    private String chipId;

    @Schema(description = "Device LAN IP address", example = "192.168.1.10")
    private String ip;

    @Schema(description = "Whether device websocket is currently online", example = "true")
    private Boolean online;

    @Schema(description = "Latest in-memory heartbeat/register timestamp in milliseconds", example = "1713062400000")
    private Long lastSeen;

    @Schema(description = "Persisted last seen time", example = "2026-06-03T10:30:00")
    private LocalDateTime lastSeenAt;
    private String garmentDetectionStatus;
    private Boolean nearby;
    private LocalDateTime lastTakenAt;
    private String trackingStatus;
}
