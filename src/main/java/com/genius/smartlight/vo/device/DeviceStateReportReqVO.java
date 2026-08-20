package com.genius.smartlight.vo.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Schema(description = "设备状态上报请求。由单片机端调用，用于同步亮度、色温、自动模式、AI 推荐和 OTA 状态")
@Data
public class DeviceStateReportReqVO {

    @Schema(description = "芯片唯一ID，后端根据该字段定位设备", example = "ABC123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "芯片ID不能为空")
    private String chipId;

    @Schema(description = "设备类型：lamp、cam、camlamp 或 cam_capture", example = "lamp", allowableValues = {"lamp", "cam", "camlamp", "cam_capture"}, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "设备类型不能为空")
    private String deviceType;

    @Schema(description = "设备局域网 IP 地址", example = "192.168.1.10")
    private String ip;

    @Schema(description = "当前亮度，范围 0-100", example = "80")
    private Integer brightness;

    @Schema(description = "当前色温，单位 K", example = "4500")
    private Integer temp;

    @Schema(description = "是否开启自动模式", example = "true")
    private Boolean autoMode;

    @Schema(description = "AI 推荐亮度，范围通常为 0-100", example = "75")
    private Integer recommendedBrightness;

    @Schema(description = "AI 推荐色温，单位 K", example = "5000")
    private Integer recommendedTemp;

    @Schema(description = "AI 识别出的面料类型", example = "cotton")
    private String fabric;

    @Schema(description = "AI 识别出的服装主色 RGB 值，格式为 R,G,B", example = "255,200,120")
    private String mainColorRgb;

    @Schema(description = "当前固件版本号", example = "1.0.0")
    private String firmwareVersion;

    @Schema(description = "当前固件版本编码", example = "10000")
    private Integer firmwareVersionCode;

    @Schema(description = "固件升级通道，例如 stable 或 test", example = "stable")
    private String firmwareChannel;

    @Schema(description = "OTA 状态，例如 idle、updating、success、failed", example = "idle")
    private String otaStatus;

    @Schema(description = "OTA progress, 0-100", example = "35")
    private Integer otaProgress;

    @Schema(description = "Device self-test result JSON")
    private Map<String, Object> selfTest;

    public void setId(String id) {
        setChipIdIfBlank(id);
    }

    public void setDeviceId(String deviceId) {
        setChipIdIfBlank(deviceId);
    }

    public void setAuto(Object auto) {
        if (this.autoMode == null) {
            this.autoMode = parseBoolean(auto);
        }
    }

    public void setFwVersion(String fwVersion) {
        if (this.firmwareVersion == null) {
            this.firmwareVersion = fwVersion;
        }
    }

    public void setFwVersionCode(Object fwVersionCode) {
        if (this.firmwareVersionCode == null) {
            this.firmwareVersionCode = parseInteger(fwVersionCode);
        }
    }

    public void setChannel(String channel) {
        if (this.firmwareChannel == null) {
            this.firmwareChannel = channel;
        }
    }

    public void setOtaProgress(Object otaProgress) {
        this.otaProgress = parseInteger(otaProgress);
    }

    private void setChipIdIfBlank(String value) {
        if ((this.chipId == null || this.chipId.isBlank()) && value != null && !value.isBlank()) {
            this.chipId = value;
        }
    }

    private Boolean parseBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        if ("1".equals(text)) {
            return true;
        }
        if ("0".equals(text)) {
            return false;
        }
        return Boolean.parseBoolean(text);
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
