package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "设备上线通告请求。设备启动或重连后调用，用于通知后端和浏览器端设备出现")
@Data
public class DeviceAnnounceReqVO {

    @Schema(description = "芯片唯一ID", example = "ABC123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "芯片ID不能为空")
    private String chipId;

    @Schema(description = "设备局域网 IP 地址", example = "192.168.1.10")
    private String ip;

    @Schema(description = "设备类型：lamp、cam 或 camlamp", example = "lamp", allowableValues = {"lamp", "cam", "camlamp"})
    private String deviceType;
}
