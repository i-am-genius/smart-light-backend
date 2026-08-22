package com.genius.smartlight.vo.device;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "cam ROI、滑轨控制灯、拍照控制器与滑轨预设配置")
public class DeviceCamRoiConfigVO {
    private String camChipId;
    @Schema(description = "实际连接 Nano 滑轨电机的 Lamp chipId")
    private String sliderLampChipId;
    @Schema(description = "负责舵机控制并向 ESP32 转发拍照任务的 cam_capture chipId")
    private String captureControllerChipId;
    @Schema(description = "SG90 服装拍摄 Pan 预置角度（0~180，中位 90）", example = "90")
    private Double garmentCapturePan;
    @Schema(description = "SG90 服装拍摄 Tilt 预置角度（0~180，中位 90）", example = "90")
    private Double garmentCaptureTilt;
    @Schema(description = "SG90 人物拍摄 Pan 预置角度（0~180，中位 90）", example = "90")
    private Double personCapturePan;
    @Schema(description = "SG90 人物拍摄 Tilt 预置角度（0~180，中位 90）", example = "90")
    private Double personCaptureTilt;
    @Schema(description = "是否由拍照控制器自动上传人流照片", example = "false")
    private Boolean flowUploadEnabled;
    @Schema(description = "自动人流拍摄间隔，单位秒", example = "30")
    private Integer flowUploadIntervalSeconds;

    @JsonIgnore
    private Double capturePan;
    @JsonIgnore
    private Double captureTilt;
    private List<DeviceCamRoiItemVO> rois = new ArrayList<>();
    @Schema(description = "每个 ROI 的唯一滑轨位置，单位 mm")
    private Map<String, Double> sliderPresets = new LinkedHashMap<>();

    @Schema(description = "每个 ROI 从 0 mm 移动到预设位置的 slow/normal/fast 实测时间，单位秒")
    private Map<String, DeviceCamSliderMoveTimeVO> sliderMoveTimes = new LinkedHashMap<>();

    @JsonIgnore
    private Map<String, DeviceCamPresetVO> legacyCapturePresets = new LinkedHashMap<>();

    @JsonIgnore
    private Map<String, DeviceCamPresetVO> legacyTrackingPresets = new LinkedHashMap<>();

    private Boolean configured;

    @JsonSetter("capturePresets")
    public void readLegacyCapturePresets(Map<String, DeviceCamPresetVO> presets) {
        legacyCapturePresets = presets == null ? new LinkedHashMap<>() : presets;
    }

    @JsonSetter("trackingPresets")
    public void readLegacyTrackingPresets(Map<String, DeviceCamPresetVO> presets) {
        legacyTrackingPresets = presets == null ? new LinkedHashMap<>() : presets;
    }

    @JsonSetter("capturePan")
    public void readLegacyCapturePan(Double value) {
        capturePan = value;
        if (garmentCapturePan == null) {
            garmentCapturePan = value;
        }
    }

    @JsonSetter("captureTilt")
    public void readLegacyCaptureTilt(Double value) {
        captureTilt = value;
        if (garmentCaptureTilt == null) {
            garmentCaptureTilt = value;
        }
    }
}
