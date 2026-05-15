package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "设备上线通告响应")
@Data
public class DeviceAnnounceRespVO {

    @Schema(description = "设备是否已被系统添加并绑定到店铺。false 时前端可提示用户添加设备", example = "true")
    private Boolean added;
}
