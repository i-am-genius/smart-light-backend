package com.genius.smartlight.vo.device;

import com.genius.smartlight.vo.ai.GarmentPartRespVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "设备信息响应。包含设备基础信息、当前灯光状态、AI 推荐结果和 OTA 状态")
@Data
public class DeviceRespVO {

    @Schema(description = "设备主键ID", example = "1")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @tools.jackson.databind.annotation.JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)
    private Long id;

    @Schema(description = "芯片唯一ID，设备控制和状态同步的匹配主键", example = "ABC123456")
    private String chipId;

    @Schema(description = "设备类型：lamp 普通灯控设备，cam 跟踪摄像头设备，camlamp 旧摄像头灯设备，cam_capture 专用拍照控制器", example = "lamp", allowableValues = {"lamp", "cam", "camlamp", "cam_capture"})
    private String deviceType;

    @Schema(description = "店内编号", example = "1")
    private String deviceNo;

    @Schema(description = "设备显示名称", example = "橱窗灯")
    private String displayName;

    @Schema(description = "设备局域网 IP 地址", example = "192.168.1.10")
    private String ip;

    @Schema(description = "Device last seen time", example = "2026-06-03T10:30:00")
    private LocalDateTime lastSeenAt;

    @Schema(description = "当前亮度，范围 0-100", example = "80")
    private Integer brightness;

    @Schema(description = "当前色温，单位 K", example = "4500")
    private Integer temp;

    @Schema(description = "是否开启自动模式", example = "true")
    private Boolean autoMode;

    @Schema(description = "是否根据最新服装坐标调整照射位置；false 使用默认服装预设", example = "false")
    private Boolean garmentAimEnabled;

    private Double garmentDefaultPan;
    private Double garmentDefaultTilt;
    private Double personDefaultPan;
    private Double personDefaultTilt;

    @Schema(description = "AI 推荐亮度，范围通常为 0-100", example = "75")
    private Integer recommendedBrightness;

    @Schema(description = "AI 推荐色温，单位 K", example = "5000")
    private Integer recommendedTemp;

    @Schema(description = "AI 识别出的面料类型", example = "cotton")
    private String fabric;

    @Schema(description = "AI 识别出的服装主色 RGB 值，格式为 R,G,B", example = "255,200,120")
    private String mainColorRgb;

    private Integer resultVersion;

    private Boolean clothDetected;

    private Boolean segmentationFallback;

    private String outfitType;

    private Integer imageWidth;

    private Integer imageHeight;

    private List<GarmentPartRespVO> garments;

    @Schema(description = "当前固件版本号", example = "1.0.0")
    private String firmwareVersion;

    @Schema(description = "当前固件版本编码，用于 OTA 版本比较", example = "10000")
    private Integer firmwareVersionCode;

    @Schema(description = "固件升级通道，例如 stable 或 test", example = "stable")
    private String firmwareChannel;

    @Schema(description = "OTA 状态，例如 idle、updating、success、failed", example = "idle")
    private String otaStatus;

    @Schema(description = "OTA progress, 0-100", example = "35")
    private Integer otaProgress;

    @Schema(description = "Device latest self-test result JSON")
    private String selfTestJson;

    @Schema(description = "Device latest self-test time", example = "2026-06-02T10:30:00")
    private LocalDateTime selfTestTime;

    @Schema(description = "创建时间", example = "2026-04-14T10:30:00")
    private LocalDateTime createTime;

    @Schema(description = "更新时间", example = "2026-04-14T11:00:00")
    private LocalDateTime updateTime;

    @Schema(description = "所属店铺ID，用于 WebSocket 按店铺推送", example = "1")
    private Long storeId;
}
