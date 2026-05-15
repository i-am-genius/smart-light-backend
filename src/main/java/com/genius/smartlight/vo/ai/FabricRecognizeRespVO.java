package com.genius.smartlight.vo.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "服装面料识别响应。包含识别标签、置信度、主色 RGB、推荐灯光参数、预览 Base64 和留档图片路径")
@Data
public class FabricRecognizeRespVO {

    @Schema(description = "识别结果标签，即面料类型", example = "cotton")
    private String label;

    @Schema(description = "识别置信度，范围通常为 0-1", example = "0.92")
    private Double confidence;

    @Schema(description = "服装主色 RGB 值，格式为 R,G,B", example = "120,80,60")
    private String mainColorRgb;

    @Schema(description = "根据面料和主色推荐的灯光亮度，范围通常为 0-100", example = "72")
    private Integer recommendedBrightness;

    @Schema(description = "根据面料和主色推荐的灯光色温，单位 K", example = "4300")
    private Integer recommendedTemp;

    @Schema(description = "是否检测到服装区域", example = "true")
    private Boolean clothDetected;

    @Schema(description = "服装检测框左上角 x 坐标", example = "120")
    private Integer clothX;

    @Schema(description = "服装检测框左上角 y 坐标", example = "80")
    private Integer clothY;

    @Schema(description = "服装检测框宽度", example = "360")
    private Integer clothW;

    @Schema(description = "服装检测框高度", example = "520")
    private Integer clothH;

    @Schema(description = "服装透明背景 PNG Base64，用于主色提取或前端预览")
    private String clothMaskedPngBase64;

    @Schema(description = "带 SegFormer 分割结果的标注图 Base64。前端可兼容带 data:image 前缀或纯 Base64 内容")
    private String annotatedImageBase64;

    @Schema(description = "原始上传图在服务器上的留档路径")
    private String originalImagePath;

    @Schema(description = "分割/标注效果图在服务器上的留档路径")
    private String annotatedImagePath;

    @Schema(description = "原图与标注图拼接对比图在服务器上的留档路径")
    private String combinedImagePath;

    @Schema(description = "原始上传图访问地址")
    private String originalImageUrl;

    @Schema(description = "分割/标注效果图访问地址")
    private String annotatedImageUrl;

    @Schema(description = "原图与标注图拼接对比图访问地址")
    private String combinedImageUrl;
}
